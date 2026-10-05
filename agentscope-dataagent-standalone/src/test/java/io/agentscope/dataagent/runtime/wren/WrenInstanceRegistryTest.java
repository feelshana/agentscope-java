/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.runtime.wren;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.dataagent.dataset.WrenProfileHome;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Locks the snapshot profile marker contract (specs/010 M4, ADR 0021): a snapshot published for
 * an external-source group pins {@code profile=ext-<id>} into {@code
 * published/wren-source.properties} and the spawn path must honor it; pre-M4 snapshots (and
 * anything unreadable or malformed) fall back to the default dataset-store profile, which is
 * what keeps existing groups working with zero migration.
 *
 * <p>Also locks the per-group call serialization (ADR 0023): the MCP stdio transport is not
 * thread-safe, so two calls racing on one instance must be serialized while distinct groups
 * must still run in parallel.
 */
class WrenInstanceRegistryTest {

    @TempDir Path tmp;

    @Test
    void readsPinnedExternalProfileFromSnapshot() throws Exception {
        Path project = tmp.resolve("published");
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("wren-source.properties"),
                "profile=ext-src9\n",
                StandardCharsets.UTF_8);

        assertThat(WrenInstanceRegistry.resolveSnapshotProfile(project, "dataagent"))
                .isEqualTo("ext-src9");
    }

    @Test
    void fallsBackToDefaultProfileWithoutMarker() {
        // Pre-M4 snapshot: only the built artifacts, no marker file.
        assertThat(WrenInstanceRegistry.resolveSnapshotProfile(tmp, "dataagent"))
                .isEqualTo("dataagent");
    }

    @Test
    void fallsBackToDefaultOnMalformedOrBlankMarker() throws Exception {
        Path project = tmp.resolve("published");
        Files.createDirectories(project);

        Files.writeString(
                project.resolve("wren-source.properties"),
                "# a comment\nother=x\n",
                StandardCharsets.UTF_8);
        assertThat(WrenInstanceRegistry.resolveSnapshotProfile(project, "dataagent"))
                .isEqualTo("dataagent");

        Files.writeString(
                project.resolve("wren-source.properties"), "profile=   \n", StandardCharsets.UTF_8);
        assertThat(WrenInstanceRegistry.resolveSnapshotProfile(project, "dataagent"))
                .isEqualTo("dataagent");
    }

    // ------------------------------------------------------------------ call serialization (ADR
    // 0023)

    @Test
    void concurrentCallsOnOneGroupAreSerialized() throws Exception {
        WrenInstanceRegistry registry = newRegistry();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        List<long[]> spans = new CopyOnWriteArrayList<>();
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.callTool(anyString(), any()))
                .thenAnswer(
                        inv ->
                                Mono.fromCallable(
                                        () -> {
                                            maxInFlight.accumulateAndGet(
                                                    inFlight.incrementAndGet(), Math::max);
                                            long enter = System.nanoTime();
                                            Thread.sleep(120);
                                            spans.add(new long[] {enter, System.nanoTime()});
                                            inFlight.decrementAndGet();
                                            return new McpSchema.CallToolResult(
                                                    List.of(new McpSchema.TextContent("ok")), null);
                                        }));
        injectInstance(registry, "g1", client);

        Thread a = new Thread(() -> registry.call("g1", "run_sql", Map.of()));
        Thread b = new Thread(() -> registry.call("g1", "query_cube", Map.of()));
        a.start();
        b.start();
        a.join(10_000);
        b.join(10_000);

        assertThat(a.isAlive() || b.isAlive()).isFalse();
        assertThat(spans).hasSize(2);
        assertThat(maxInFlight.get())
                .as("both calls share one instance, so they must never overlap")
                .isEqualTo(1);
        assertThat(spans.get(0)[1]).isLessThanOrEqualTo(spans.get(1)[0]);
    }

    @Test
    void distinctGroupsStillRunInParallel() throws Exception {
        WrenInstanceRegistry registry = newRegistry();
        CountDownLatch bothStarted = new CountDownLatch(2);
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.callTool(anyString(), any()))
                .thenAnswer(
                        inv ->
                                Mono.fromCallable(
                                        () -> {
                                            bothStarted.countDown();
                                            // If the registry serialized globally (bug), the first
                                            // call would wait forever for the second to start.
                                            assertThat(bothStarted.await(10, TimeUnit.SECONDS))
                                                    .isTrue();
                                            return new McpSchema.CallToolResult(
                                                    List.of(new McpSchema.TextContent("ok")), null);
                                        }));
        injectInstance(registry, "g1", client);
        injectInstance(registry, "g2", client);

        Thread a = new Thread(() -> registry.call("g1", "run_sql", Map.of()));
        Thread b = new Thread(() -> registry.call("g2", "run_sql", Map.of()));
        a.start();
        b.start();
        a.join(15_000);
        b.join(15_000);

        assertThat(a.isAlive() || b.isAlive()).isFalse();
    }

    private static WrenInstanceRegistry newRegistry() {
        WrenProperties props = mock(WrenProperties.class);
        when(props.timeout()).thenReturn(Duration.ofSeconds(5));
        return new WrenInstanceRegistry(props, mock(WrenProfileHome.class));
    }

    /** Puts a mock-backed instance into the registry so {@code call} never spawns a process. */
    @SuppressWarnings("unchecked")
    private static void injectInstance(
            WrenInstanceRegistry registry, String groupId, McpClientWrapper client)
            throws Exception {
        Field instancesField = WrenInstanceRegistry.class.getDeclaredField("instances");
        instancesField.setAccessible(true);
        Map<String, Object> instances = (Map<String, Object>) instancesField.get(registry);
        Class<?> instanceClass =
                Class.forName("io.agentscope.dataagent.runtime.wren.WrenInstanceRegistry$Instance");
        Constructor<?> ctor =
                instanceClass.getDeclaredConstructor(Path.class, McpClientWrapper.class);
        ctor.setAccessible(true);
        instances.put(groupId, ctor.newInstance(null, client));
    }
}
