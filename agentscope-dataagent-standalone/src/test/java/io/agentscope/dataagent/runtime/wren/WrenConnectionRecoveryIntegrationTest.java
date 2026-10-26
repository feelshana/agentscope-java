package io.agentscope.dataagent.runtime.wren;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.WrenProfileHome;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Opt-in real Wren checks: interrupt only a disposable proxy, never the upstream service. */
class WrenConnectionRecoveryIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"mysql", "doris"})
    void realProcessRecoversWithoutPublishAndBoundsRepeatedOutage(String name) throws Exception {
        String configFile = System.getProperty("dataagent.test.recovery.config", "");
        assumeTrue(!configFile.isBlank(), "Requires an explicit private read-only fixture config");
        JsonNode config = json.readTree(Files.readString(Path.of(configFile)));
        var fixture = config.path("targets").path(name);
        Path root = Path.of(config.path("root").asText());
        assertThat(root.getFileName().toString()).startsWith("wren-recovery-");
        Path project = root.resolve(name + "/published");
        byte[] manifest = Files.readAllBytes(project.resolve("target/mdl.json"));
        var args = Map.<String, Object>of("sql", "SELECT COUNT(*) AS n FROM products", "limit", 1);
        Map<String, Object> report = new LinkedHashMap<>();
        try (var proxy = new Proxy(fixture.path("upstreamPort").asInt())) {
            Path home = root.resolve(name + "-home");
            Files.createDirectories(home);
            Map<String, Object> profile = json.convertValue(fixture.path("profile"), Map.class);
            profile.put("port", Integer.toString(proxy.server.getLocalPort()));
            json.writeValue(
                    home.resolve("profiles.yml").toFile(),
                    Map.of("active", "recovery", "profiles", Map.of("recovery", profile)));
            var props =
                    new WrenProperties(
                            config.path("executable").asText(),
                            root.toString(),
                            "recovery",
                            "mysql",
                            30);
            var profileHome = mock(WrenProfileHome.class);
            when(profileHome.ensure()).thenReturn(home);
            AtomicInteger created = new AtomicInteger();
            List<McpClientWrapper> clients = new CopyOnWriteArrayList<>();
            var registry =
                    new WrenInstanceRegistry(props, profileHome) {
                        @Override
                        McpClientWrapper createClient(String group, Path path, String selected) {
                            var client = super.createClient(group, path, selected);
                            created.incrementAndGet();
                            clients.add(client);
                            return client;
                        }
                    };
            try {
                assertCount(registry.call(name, "run_sql", args));
                proxy.dropConnections();
                var nativeFailure = clients.get(0).callTool("run_sql", args).block(props.timeout());
                assertThat(nativeFailure).isNotNull();
                assertThat(nativeFailure.isError()).isTrue();
                report.put(
                        "oldProcessFailure",
                        nativeFailure.content().stream()
                                .filter(McpSchema.TextContent.class::isInstance)
                                .map(McpSchema.TextContent.class::cast)
                                .map(McpSchema.TextContent::text)
                                .toList());
                try (var conn =
                                DriverManager.getConnection(
                                        "jdbc:mysql://127.0.0.1:"
                                                + proxy.server.getLocalPort()
                                                + "/"
                                                + profile.get("database")
                                                + "?sslMode=DISABLED&connectTimeout=3000&socketTimeout=3000",
                                        profile.get("user").toString(),
                                        profile.get("password").toString());
                        var stmt = conn.createStatement();
                        var rows = stmt.executeQuery("SELECT COUNT(*) FROM products")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(100);
                }
                long start = System.nanoTime();
                assertCount(registry.callAuthorized(name, "run_sql", args, () -> {}));
                report.put("recoveryMillis", (System.nanoTime() - start) / 1_000_000);
                assertThat(created.get()).isEqualTo(2);
                report.put("freshConnectionSucceeded", true);
                report.put("recoveredWithoutPublish", true);

                // A persistent outage gets one replacement, then blocks without spawning again.
                proxy.enabled = false;
                proxy.dropConnections();
                start = System.nanoTime();
                var failed = registry.call(name, "run_sql", args);
                assertThat(failed.ok()).isFalse();
                assertThat(failed.payload()).contains("已停止自动重试");
                assertThat(created.get()).isEqualTo(3);
                report.put("persistentFailureMillis", (System.nanoTime() - start) / 1_000_000);
                report.put("persistentFailure", failed.payload());
                proxy.enabled = true;
                start = System.nanoTime();
                assertThatThrownBy(() -> registry.listToolsAuthorized(name, () -> {}))
                        .isInstanceOf(DatasetException.class)
                        .hasMessageContaining("30秒");
                for (int i = 0; i < 10; i++)
                    assertThatThrownBy(() -> registry.call(name, "run_sql", args))
                            .isInstanceOf(DatasetException.class)
                            .hasMessageContaining("30秒");
                report.put("tenCooldownCallsMillis", (System.nanoTime() - start) / 1_000_000);
                assertThat(created.get()).isEqualTo(3);
                // Exercise the real cooldown, not a reflected clock override.
                Thread.sleep(30_100);
                assertCount(registry.call(name, "run_sql", args));
                assertThat(created.get()).isEqualTo(4);
                report.put("recoveredAfterRealCooldown", true);
                assertThat(
                                registry.call(
                                                name,
                                                "dry_run",
                                                Map.of("sql", "SELECT COUNT(*) FROM products"))
                                        .ok())
                        .isTrue();
                proxy.dropConnections();
                assertThat(
                                registry.call(
                                                name,
                                                "dry_run",
                                                Map.of("sql", "SELECT COUNT(*) FROM products"))
                                        .ok())
                        .isTrue();
                assertThat(created.get()).isEqualTo(5);
                report.put("dryRunRecovered", true);
                var cubeArgs =
                        Map.<String, Object>of(
                                "cube",
                                name.equals("mysql") ? "sales" : "sales_order_lines",
                                "measures",
                                List.of("sales_amount"));
                var cubeBefore = registry.call(name, "query_cube", cubeArgs);
                assertThat(cubeBefore.ok()).as(cubeBefore.payload()).isTrue();
                proxy.dropConnections();
                var cubeAfter = registry.call(name, "query_cube", cubeArgs);
                assertThat(cubeAfter.ok()).as(cubeAfter.payload()).isTrue();
                assertThat(json.readTree(cubeAfter.payload()).path("rows"))
                        .isEqualTo(json.readTree(cubeBefore.payload()).path("rows"));
                assertThat(created.get()).isEqualTo(6);
                report.put("cubeRecoveredWithIdenticalRows", true);
                report.put("createdProcesses", created.get());
            } finally {
                registry.close();
            }
        }
        assertThat(Files.readAllBytes(project.resolve("target/mdl.json"))).isEqualTo(manifest);
        report.put("manifestUnchanged", true);
        report.put("passed", true);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(root.resolve(name + "-result.json").toFile(), report);
    }

    private void assertCount(WrenQueryGateway.WrenCallResult result) throws Exception {
        assertThat(result.ok()).as(result.payload()).isTrue();
        assertThat(json.readTree(result.payload()).path("rows").get(0).path("n").asInt())
                .isEqualTo(100);
    }

    private static final class Proxy implements AutoCloseable {
        final ServerSocket server;
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final ExecutorService workers =
                Executors.newCachedThreadPool(
                        r -> {
                            var thread = new Thread(r, "wren-recovery-test-proxy");
                            thread.setDaemon(true);
                            return thread;
                        });
        volatile boolean enabled = true;

        Proxy(int upstreamPort) throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            workers.submit(
                    () -> {
                        while (!server.isClosed()) {
                            try {
                                var downstream = server.accept();
                                sockets.add(downstream);
                                workers.submit(() -> connect(downstream, upstreamPort));
                            } catch (IOException ignored) {
                                return;
                            }
                        }
                    });
        }

        private void connect(Socket downstream, int port) {
            Socket upstream = new Socket();
            sockets.add(upstream);
            try {
                if (!enabled) {
                    closeSocket(downstream);
                    closeSocket(upstream);
                    return;
                }
                upstream.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                workers.submit(() -> copy(upstream, downstream));
                copy(downstream, upstream);
            } catch (IOException ignored) {
                closeSocket(downstream);
                closeSocket(upstream);
            }
        }

        private void copy(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException ignored) {
                /* Intentional fault injection. */
            } finally {
                closeSocket(from);
                closeSocket(to);
            }
        }

        private void closeSocket(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                /* Best effort. */
            }
            sockets.remove(socket);
        }

        void dropConnections() {
            List.copyOf(sockets).forEach(this::closeSocket);
        }

        @Override
        public void close() throws IOException {
            server.close();
            dropConnections();
            workers.shutdownNow();
        }
    }
}
