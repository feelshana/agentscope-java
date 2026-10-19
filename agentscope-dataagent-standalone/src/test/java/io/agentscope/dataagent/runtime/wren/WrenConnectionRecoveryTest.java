package io.agentscope.dataagent.runtime.wren;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.WrenProfileHome;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway.WrenCallResult;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class WrenConnectionRecoveryTest {
    @TempDir Path tmp;
    private static final Map<String, Object> ARGS = Map.of("sql", "SELECT COUNT(*) FROM products");

    private static String error(String tool, String diagnostic) {
        return "Error executing tool "
                + tool
                + ": [GENERIC_USER_ERROR] "
                + diagnostic
                + " phase=SQL_EXECUTION";
    }

    private static McpSchema.CallToolResult result(boolean ok, String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), !ok);
    }

    private static McpClientWrapper client(boolean ok, String text) {
        var client = mock(McpClientWrapper.class);
        when(client.callTool(anyString(), any())).thenReturn(Mono.just(result(ok, text)));
        when(client.listTools()).thenReturn(Mono.just(List.of()));
        return client;
    }

    private static McpClientWrapper broken(String tool) {
        return client(false, error(tool, "(2006, 'Server has gone away')"));
    }

    private Fixture registry(McpClientWrapper... clients) throws Exception {
        var props = mock(WrenProperties.class);
        when(props.timeout()).thenReturn(Duration.ofSeconds(5));
        when(props.maxInstances()).thenReturn(2);
        when(props.callWaitSeconds()).thenReturn(2);
        when(props.profile()).thenReturn("fixture");
        when(props.instanceIdle()).thenReturn(Duration.ofHours(1));
        for (String group : List.of("g1", "g2")) {
            var root = tmp.resolve(group);
            Files.createDirectories(root.resolve("published/target"));
            Files.writeString(root.resolve("published/target/mdl.json"), "{}");
            when(props.groupRoot(group)).thenReturn(root);
        }
        return new Fixture(props, clients);
    }

    private static final class Fixture extends WrenInstanceRegistry {
        final Queue<McpClientWrapper> clients;
        final AtomicInteger created = new AtomicInteger();

        Fixture(WrenProperties props, McpClientWrapper... clients) {
            super(props, mock(WrenProfileHome.class));
            this.clients = new ArrayDeque<>(List.of(clients));
        }

        @Override
        McpClientWrapper createClient(String group, Path project, String profile) {
            created.incrementAndGet();
            assertThat(project.getFileName().toString()).isEqualTo("published");
            assertThat(profile).isEqualTo("fixture");
            return clients.remove();
        }

        @SuppressWarnings("unchecked")
        void expireCooldown() {
            ((Map<String, Long>) ReflectionTestUtils.getField(this, "recoveryNotBefore"))
                    .replaceAll((key, value) -> System.nanoTime() - 1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run_sql", "dry_run", "query_cube"})
    void recoversOnceAndReusesReplacement(String tool) throws Exception {
        var first = broken(tool);
        var next = client(true, "100");
        var registry = registry(first, next);
        try {
            assertThat(registry.call("g1", tool, ARGS).payload()).isEqualTo("100");
            assertThat(registry.call("g1", tool, ARGS).ok()).isTrue();
            assertThat(registry.created.get()).isEqualTo(2);
            verify(first).callTool(tool, ARGS);
            verify(first).close();
            verify(next, times(2)).callTool(tool, ARGS);
            verify(next, never()).close();
        } finally {
            registry.close();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "(2006, 'Server has gone away')",
                "(2006, 'MySQL server has gone away')",
                "(2013, 'Lost connection to server during query')",
                "(2055, 'Lost connection to MySQL server at localhost')",
                "(2003, \"Can't connect to MySQL server on localhost\")",
                "(2002, 'Connection refused')"
            })
    void recognizesOnlySupportedConnectionDiagnostics(String diagnostic) {
        assertThat(
                        WrenConnectionFailure.matches(
                                "run_sql", new WrenCallResult(false, error("run_sql", diagnostic))))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "(1064, 'SQL syntax error')",
                "(1045, 'Access denied')",
                "(1049, 'Unknown database')",
                "(1146, 'Table does not exist')",
                "(1054, 'Unknown column')",
                "(1205, 'Lock wait timeout')",
                "(2006, 'Unknown error')",
                "(2013, 'Statement execution timeout')",
                "SQL contains (2006, 'Server has gone away')",
                "Connection reset by peer"
            })
    void businessAndUnrecognizedErrorsKeepInstance(String diagnostic) throws Exception {
        var first = client(false, error("run_sql", diagnostic));
        var registry = registry(first);
        try {
            assertThat(registry.call("g1", "run_sql", ARGS).ok()).isFalse();
            assertThat(registry.created.get()).isEqualTo(1);
            verify(first, never()).close();
        } finally {
            registry.close();
        }
    }

    @Test
    void successEchoAndOtherToolsNeverTriggerReplay() {
        var payload = error("run_sql", "(2006, 'Server has gone away')");
        assertThat(WrenConnectionFailure.matches("run_sql", new WrenCallResult(true, payload)))
                .isFalse();
        assertThat(WrenConnectionFailure.matches("store_query", new WrenCallResult(false, payload)))
                .isFalse();
        assertThat(WrenConnectionFailure.matches("dry_run", new WrenCallResult(false, payload)))
                .isFalse();
        assertThat(
                        WrenConnectionFailure.matches(
                                "run_sql", new WrenCallResult(false, "Bad SQL: " + payload)))
                .isFalse();
    }

    @Test
    void connectionOpenFailureBeforeEngineWrappingIsRecognized() {
        assertThat(
                        WrenConnectionFailure.matches(
                                "run_sql",
                                new WrenCallResult(
                                        false,
                                        "Error executing tool run_sql: (2003, \"Can't connect to"
                                                + " MySQL server on '127.0.0.1' (10061)\")")))
                .isTrue();
    }

    @Test
    void persistentFailureIsBoundedAndLaterCallCanRecover() throws Exception {
        var first = broken("run_sql");
        var next = broken("run_sql");
        var healthy = client(true, "100");
        var registry = registry(first, next, healthy);
        try {
            var failed = registry.call("g1", "run_sql", ARGS);
            assertThat(failed.ok()).isFalse();
            assertThat(failed.payload()).contains("2006", "已停止自动重试");
            for (int i = 0; i < 10; i++)
                assertThatThrownBy(() -> registry.call("g1", "run_sql", ARGS))
                        .isInstanceOf(DatasetException.class)
                        .hasMessageContaining("30秒");
            assertThat(registry.created.get()).isEqualTo(2);
            assertThatThrownBy(() -> registry.listTools("g1"))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("30秒");
            verify(first).close();
            verify(next).close();
            registry.expireCooldown();
            assertThat(registry.call("g1", "run_sql", ARGS).payload()).isEqualTo("100");
            assertThat(registry.created.get()).isEqualTo(3);
            assertThat(
                            ((Semaphore) ReflectionTestUtils.getField(registry, "slots"))
                                    .availablePermits())
                    .isEqualTo(1);
        } finally {
            registry.close();
        }
        assertThat(((Semaphore) ReflectionTestUtils.getField(registry, "slots")).availablePermits())
                .isEqualTo(2);
    }

    @Test
    void revocationAfterFailedQueryCannotRebuildSharedEngine() throws Exception {
        var first = broken("run_sql");
        var checks = new AtomicInteger();
        var registry = registry(first);
        try {
            assertThatThrownBy(
                            () ->
                                    registry.callAuthorized(
                                            "g1",
                                            "run_sql",
                                            ARGS,
                                            () -> {
                                                if (checks.incrementAndGet() == 3)
                                                    throw new DatasetException("已撤权", 403);
                                            }))
                    .isInstanceOf(DatasetException.class)
                    .hasMessage("已撤权");
            assertThat(registry.created.get()).isEqualTo(1);
            verify(first, never()).close();
        } finally {
            registry.close();
        }
    }

    @Test
    void revocationDuringReplacementCreationPreventsQuery() throws Exception {
        var first = broken("run_sql");
        var next = client(true, "100");
        var checks = new AtomicInteger();
        var registry = registry(first, next);
        try {
            assertThatThrownBy(
                            () ->
                                    registry.callAuthorized(
                                            "g1",
                                            "run_sql",
                                            ARGS,
                                            () -> {
                                                if (checks.incrementAndGet() == 5)
                                                    throw new DatasetException("已撤权", 403);
                                            }))
                    .isInstanceOf(DatasetException.class)
                    .hasMessage("已撤权");
            assertThat(registry.created.get()).isEqualTo(2);
            verify(next, never()).callTool(anyString(), any());
        } finally {
            registry.close();
        }
    }

    @Test
    void cancelledFailureDoesNotRebuild() throws Exception {
        var first = broken("run_sql");
        when(first.callTool(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            Thread.currentThread().interrupt();
                            return Mono.just(
                                    result(
                                            false,
                                            error("run_sql", "(2006, 'Server has gone away')")));
                        });
        var registry = registry(first);
        try {
            assertThatThrownBy(() -> registry.call("g1", "run_sql", ARGS))
                    .isInstanceOf(CancellationException.class);
            assertThat(registry.created.get()).isEqualTo(1);
            verify(first, never()).close();
        } finally {
            Thread.interrupted();
            registry.close();
        }
    }

    @Test
    void replacementBusinessErrorDoesNotCauseAnotherRebuild() throws Exception {
        var registry =
                registry(
                        broken("run_sql"),
                        client(false, error("run_sql", "(1054, 'Unknown column')")));
        try {
            assertThat(registry.call("g1", "run_sql", ARGS).payload()).contains("1054");
            assertThat(registry.call("g1", "run_sql", ARGS).payload()).contains("1054");
            assertThat(registry.created.get()).isEqualTo(2);
        } finally {
            registry.close();
        }
    }

    @Test
    void transportFailureDoesNotReplayButInvalidates() throws Exception {
        var first = broken("run_sql");
        when(first.callTool(anyString(), any()))
                .thenReturn(Mono.error(new IllegalStateException("transport down")));
        var registry = registry(first, client(true, "100"));
        try {
            assertThatThrownBy(() -> registry.call("g1", "run_sql", ARGS))
                    .isInstanceOf(DatasetException.class);
            assertThat(registry.created.get()).isEqualTo(1);
            verify(first).close();
            assertThat(registry.call("g1", "run_sql", ARGS).ok()).isTrue();
        } finally {
            registry.close();
        }
    }

    @Test
    void transportFailureDuringRecoveryStillEnforcesCooldown() throws Exception {
        var replacement = broken("run_sql");
        when(replacement.callTool(anyString(), any()))
                .thenReturn(Mono.error(new IllegalStateException("replacement transport failed")));
        var registry = registry(broken("run_sql"), replacement);
        try {
            assertThatThrownBy(() -> registry.call("g1", "run_sql", ARGS))
                    .isInstanceOf(DatasetException.class);
            assertThatThrownBy(() -> registry.listTools("g1"))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("30秒");
            assertThatThrownBy(() -> registry.call("g1", "run_sql", ARGS))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("30秒");
            assertThat(registry.created.get()).isEqualTo(2);
            verify(replacement).close();
        } finally {
            registry.close();
        }
    }

    @Test
    void failedReplacementStartupStillEnforcesCooldownAndReleasesBudget() throws Exception {
        var registry = registry(broken("run_sql"));
        try {
            assertThatThrownBy(() -> registry.call("g1", "run_sql", ARGS))
                    .isInstanceOf(java.util.NoSuchElementException.class);
            assertThatThrownBy(() -> registry.listTools("g1"))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("30秒");
            assertThat(registry.created.get()).isEqualTo(2);
            assertThat(
                            ((Semaphore) ReflectionTestUtils.getField(registry, "slots"))
                                    .availablePermits())
                    .isEqualTo(2);
        } finally {
            registry.close();
        }
    }

    @Test
    void queuedSameGroupCallsShareOneRecoveryWhileOtherGroupRuns() throws Exception {
        var first = broken("run_sql");
        var next = client(true, "100");
        var other = client(true, "200");
        var registry = registry(first, next, other);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(next.callTool(anyString(), any()))
                .thenAnswer(
                        inv ->
                                Mono.fromCallable(
                                        () -> {
                                            entered.countDown();
                                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                                            return result(true, "100");
                                        }));
        var pool = Executors.newFixedThreadPool(3);
        try {
            var a = pool.submit(() -> registry.call("g1", "run_sql", ARGS));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var b = pool.submit(() -> registry.call("g1", "run_sql", ARGS));
            assertThat(
                            pool.submit(() -> registry.call("g2", "run_sql", ARGS))
                                    .get(2, TimeUnit.SECONDS)
                                    .payload())
                    .isEqualTo("200");
            release.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS).payload()).isEqualTo("100");
            assertThat(b.get(5, TimeUnit.SECONDS).payload()).isEqualTo("100");
            assertThat(registry.created.get()).isEqualTo(3);
            verify(first).callTool("run_sql", ARGS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            registry.close();
        }
    }
}
