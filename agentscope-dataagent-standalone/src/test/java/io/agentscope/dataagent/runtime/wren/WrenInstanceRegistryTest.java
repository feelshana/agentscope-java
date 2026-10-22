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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.dataagent.dataset.DatasetException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    void recallWarmupUsesPooledInstanceAndEvictionClearsReadiness() throws Exception {
        var registry = newRegistry();
        var client = mock(McpClientWrapper.class);
        when(client.callTool(eq("recall_queries"), any()))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(new McpSchema.TextContent("{\"matches\":[]}")),
                                        false)));
        injectInstance(registry, "a", client);
        assertThat(registry.recallWarm("a")).isFalse();
        var checks = new AtomicInteger();
        registry.warmRecall("a", checks::incrementAndGet);
        assertThat(checks.get()).isGreaterThanOrEqualTo(2);
        assertThat(registry.recallWarm("a")).isTrue();
        registry.callAuthorized(
                "a", "recall_queries", Map.of("question", "业务问题"), checks::incrementAndGet);
        verify(client, times(2)).callTool(eq("recall_queries"), any());
        registry.invalidate("a");
        assertThat(registry.recallWarm("a")).isFalse();
        registry.close();
    }

    @Test
    void instructionsCacheIsInstanceScopedAndNeverSkipsAuthorization() throws Exception {
        var registry = newRegistry();
        var client = mock(McpClientWrapper.class);
        when(client.callTool("get_instructions", Map.of()))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(new McpSchema.TextContent("a")), false)));
        var other = mock(McpClientWrapper.class);
        when(other.callTool("get_instructions", Map.of()))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(new McpSchema.TextContent("b")), false)));
        injectInstance(registry, "a", client);
        injectInstance(registry, "b", other);
        assertThat(registry.callAuthorized("a", "get_instructions", Map.of(), () -> {}).payload())
                .isEqualTo("a");
        assertThat(registry.callAuthorized("a", "get_instructions", Map.of(), () -> {}).payload())
                .isEqualTo("a");
        assertThatThrownBy(
                        () ->
                                registry.callAuthorized(
                                        "a",
                                        "get_instructions",
                                        Map.of(),
                                        () -> {
                                            throw new DatasetException("无权访问", 403);
                                        }))
                .isInstanceOf(DatasetException.class);
        assertThat(registry.callAuthorized("b", "get_instructions", Map.of(), () -> {}).payload())
                .isEqualTo("b");
        verify(client, times(1)).callTool("get_instructions", Map.of());
        registry.invalidate("a");
        injectInstance(registry, "a", client);
        registry.callAuthorized("a", "get_instructions", Map.of(), () -> {});
        verify(client, times(2)).callTool("get_instructions", Map.of());
        registry.close();
    }

    @Test
    void failedInstructionsAreNeverCached() throws Exception {
        var registry = newRegistry();
        var client = mock(McpClientWrapper.class);
        when(client.callTool("get_instructions", Map.of()))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(new McpSchema.TextContent("error")), true)));
        injectInstance(registry, "a", client);
        assertThat(registry.call("a", "get_instructions", Map.of()).ok()).isFalse();
        assertThat(registry.call("a", "get_instructions", Map.of()).ok()).isFalse();
        verify(client, times(2)).callTool("get_instructions", Map.of());
        registry.close();
    }

    @Test
    void discoveryIsCachedPerInstanceAndInvalidationDropsIt() throws Exception {
        var registry = newRegistry();
        var first = mock(McpClientWrapper.class);
        var second = mock(McpClientWrapper.class);
        var tool = mock(McpSchema.Tool.class);
        when(first.listTools()).thenReturn(Mono.just(List.of(tool)));
        when(second.listTools()).thenReturn(Mono.just(List.of()));
        try {
            injectInstance(registry, "g1", first);
            injectInstance(registry, "g2", second);
            assertThat(registry.listTools("g1")).containsExactly(tool);
            assertThat(registry.listTools("g1")).containsExactly(tool);
            assertThat(registry.listTools("g2")).isEmpty();
            verify(first, times(1)).listTools();
            registry.invalidate("g1");
            injectInstance(registry, "g1", second);
            assertThat(registry.listTools("g1")).isEmpty();
            verify(second, times(2)).listTools();
        } finally {
            registry.close();
        }
    }

    @Test
    void failedDiscoveryIsNotCached() throws Exception {
        var registry = newRegistry();
        var client = mock(McpClientWrapper.class);
        when(client.listTools())
                .thenReturn(Mono.error(new IllegalStateException("offline")), Mono.just(List.of()));
        try {
            injectInstance(registry, "g1", client);
            assertThatThrownBy(() -> registry.listTools("g1")).hasMessage("offline");
            assertThat(registry.listTools("g1")).isEmpty();
            verify(client, times(2)).listTools();
        } finally {
            registry.close();
        }
    }

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

    @Test
    @SuppressWarnings("unchecked")
    void revokedQueuedDiscoveryCannotReachMcpOrInvalidateSharedInstance() throws Exception {
        WrenInstanceRegistry registry = newRegistry();
        McpClientWrapper client = mock(McpClientWrapper.class);
        injectInstance(registry, "g1", client);
        Field gatesField = WrenInstanceRegistry.class.getDeclaredField("callGates");
        gatesField.setAccessible(true);
        var gates =
                (Map<String, java.util.concurrent.locks.ReentrantLock>) gatesField.get(registry);
        var gate =
                gates.computeIfAbsent(
                        "g1", ignored -> new java.util.concurrent.locks.ReentrantLock(true));
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread queued =
                new Thread(
                        () -> {
                            try {
                                registry.listToolsAuthorized(
                                        "g1",
                                        () -> {
                                            if (revoked.get())
                                                throw new DatasetException("知识库不存在或未授权", 404);
                                        });
                            } catch (Throwable e) {
                                failure.set(e);
                            }
                        });
        gate.lock();
        try {
            queued.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queued.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertThat(queued.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            revoked.set(true);
        } finally {
            gate.unlock();
        }
        queued.join(5000);
        assertThat(queued.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(DatasetException.class);
        verify(client, never()).listTools();
        when(client.listTools()).thenReturn(Mono.just(List.of()));
        assertThat(registry.listTools("g1")).isEmpty();
        registry.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void revokedQueuedCallCannotReachMcpOrInvalidateSharedInstance() throws Exception {
        WrenInstanceRegistry registry = newRegistry();
        McpClientWrapper client = mock(McpClientWrapper.class);
        injectInstance(registry, "g1", client);
        Field gatesField = WrenInstanceRegistry.class.getDeclaredField("callGates");
        gatesField.setAccessible(true);
        var gates =
                (Map<String, java.util.concurrent.locks.ReentrantLock>) gatesField.get(registry);
        var gate =
                gates.computeIfAbsent(
                        "g1", ignored -> new java.util.concurrent.locks.ReentrantLock(true));
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread queued =
                new Thread(
                        () -> {
                            try {
                                registry.callAuthorized(
                                        "g1",
                                        "run_sql",
                                        Map.of(),
                                        () -> {
                                            if (revoked.get())
                                                throw new DatasetException("知识库不存在或未授权", 404);
                                        });
                            } catch (Throwable e) {
                                failure.set(e);
                            }
                        });
        gate.lock();
        try {
            queued.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queued.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertThat(queued.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            revoked.set(true);
        } finally {
            gate.unlock();
        }
        queued.join(5000);
        assertThat(queued.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(DatasetException.class);
        verify(client, never()).callTool(anyString(), any());
        when(client.callTool(anyString(), any()))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(new McpSchema.TextContent("ok")), null)));
        assertThat(registry.call("g1", "run_sql", Map.of()).ok()).isTrue();
        registry.close();
    }

    @Test
    void authorizationIsRecheckedAfterAcquireAndDenialKeepsInstance() throws Exception {
        WrenInstanceRegistry registry = newRegistry();
        McpClientWrapper client = mock(McpClientWrapper.class);
        injectInstance(registry, "g1", client);
        AtomicInteger checks = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                registry.callAuthorized(
                                        "g1",
                                        "run_sql",
                                        Map.of(),
                                        () -> {
                                            if (checks.incrementAndGet() == 2)
                                                throw new DatasetException("已撤权", 404);
                                        }))
                .isInstanceOf(DatasetException.class);
        assertThat(checks.get()).isEqualTo(2);
        verify(client, never()).callTool(anyString(), any());
        when(client.callTool(anyString(), any()))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(new McpSchema.TextContent("ok")), null)));
        assertThat(registry.call("g1", "run_sql", Map.of()).ok()).isTrue();
        registry.close();
    }

    private static WrenInstanceRegistry newRegistry() {
        WrenProperties props = mock(WrenProperties.class);
        when(props.timeout()).thenReturn(Duration.ofSeconds(5));
        when(props.maxInstances()).thenReturn(16);
        when(props.callWaitSeconds()).thenReturn(30);
        return new WrenInstanceRegistry(props, mock(WrenProfileHome.class));
    }

    @Test
    void failedEngineCloseRetainsBudgetUntilRetryConfirmsCleanup() throws Exception {
        WrenInstanceRegistry registry = newRegistry();
        McpClientWrapper client = mock(McpClientWrapper.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("close failed"))
                .doNothing()
                .when(client)
                .close();
        injectInstance(registry, "retry", client);
        Object instance =
                ((Map<?, ?>)
                                org.springframework.test.util.ReflectionTestUtils.getField(
                                        registry, "instances"))
                        .get("retry");
        ((AtomicBoolean)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                instance, "reserved"))
                .set(true);
        var slots =
                (java.util.concurrent.Semaphore)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                registry, "slots");
        slots.acquire();
        int before = slots.availablePermits();
        registry.invalidate("retry");
        assertThat(slots.availablePermits()).isEqualTo(before);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(registry, "sweepIdle");
        assertThat(slots.availablePermits()).isEqualTo(before + 1);
        registry.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullPoolNeverEvictsAnExecutingEngineAndReclaimsItAfterRelease() throws Exception {
        WrenProperties props = mock(WrenProperties.class);
        when(props.maxInstances()).thenReturn(1);
        WrenInstanceRegistry registry =
                new WrenInstanceRegistry(props, mock(WrenProfileHome.class));
        McpClientWrapper client = mock(McpClientWrapper.class);
        injectInstance(registry, "busy", client);
        var instances =
                (Map<String, Object>)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                registry, "instances");
        var instance = instances.get("busy");
        ((AtomicBoolean)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                instance, "reserved"))
                .set(true);
        var slots =
                (java.util.concurrent.Semaphore)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                registry, "slots");
        slots.acquire();
        var gates =
                (Map<String, java.util.concurrent.locks.ReentrantLock>)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                registry, "callGates");
        var gate = new java.util.concurrent.locks.ReentrantLock();
        gates.put("busy", gate);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread worker =
                new Thread(
                        () -> {
                            gate.lock();
                            try {
                                entered.countDown();
                                release.await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                gate.unlock();
                            }
                        });
        worker.start();
        try {
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(
                            () ->
                                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                            registry, "reserveSlot", "next"))
                    .isInstanceOf(DatasetException.class);
            verify(client, never()).close();
            assertThat(instances).containsKey("busy");
        } finally {
            release.countDown();
            worker.join(2000);
        }
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                registry, "reserveSlot", "next");
        verify(client).close();
        assertThat(instances).doesNotContainKey("busy");
        assertThat(slots.availablePermits()).isZero();
        slots.release();
        registry.close();
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
