package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

/** Opt-in verification against a dedicated local database; never uses application defaults. */
@EnabledIfSystemProperty(named = "dataagent.test.state.config", matches = ".+")
class AgentStateMysqlIntegrationTest {
    static HikariDataSource source;

    @BeforeAll
    static void start() throws Exception {
        var properties = new Properties();
        try (var input =
                Files.newInputStream(Path.of(System.getProperty("dataagent.test.state.config")))) {
            properties.load(input);
        }
        var config = new HikariConfig();
        config.setJdbcUrl(properties.getProperty("url"));
        config.setUsername(properties.getProperty("username"));
        config.setPassword(properties.getProperty("password"));
        config.setMaximumPoolSize(12);
        config.setPoolName("agent-state-verification");
        source = new HikariDataSource(config);
        try (var connection = source.getConnection()) {
            if (!connection.getCatalog().startsWith("dataagent_state_verify_")) {
                source.close();
                throw new IllegalStateException(
                        "A dedicated dataagent_state_verify_ database is required");
            }
        }
        store("update");
    }

    @AfterAll
    static void close() {
        if (source != null) source.close();
    }

    static AgentStateStore store(String ddl) throws Exception {
        return new AgentStateConfig()
                .durableAgentStateStore(
                        source,
                        new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", ddl));
    }

    @Test
    void actualMysqlKeepsLatestSnapshotAndChecksVersionsAndExactNamespaces() throws Exception {
        var store = store("validate");
        assertThat(store).isInstanceOf(MysqlAgentStateStore.class);
        String user = "case-owner";
        String session = "same-id";
        store.delete(user, session);
        store.delete("CASE-OWNER", session);
        long v1 = store.saveIfVersion(user, session, "agent_state", state("first"), 0);
        assertThat(v1).isEqualTo(1);
        assertThat(store.saveIfVersion(user, session, "agent_state", state("latest"), v1))
                .isEqualTo(2);
        assertThat(store.saveIfVersion(user, session, "agent_state", state("stale"), v1))
                .isEqualTo(AgentStateStore.UNVERSIONED);
        store.save("CASE-OWNER", session, "agent_state", state("other-owner"));
        var fresh = store("validate");
        assertThat(
                        fresh.get(user, session, "agent_state", AgentState.class)
                                .orElseThrow()
                                .getSummary())
                .isEqualTo("latest");
        assertThat(
                        fresh.get("CASE-OWNER", session, "agent_state", AgentState.class)
                                .orElseThrow()
                                .getSummary())
                .isEqualTo("other-owner");
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM dataagent_agent_state WHERE session_id=?")) {
            statement.setString(1, user + ":" + session);
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
        fresh.delete(user, session);
        assertThat(store.exists(user, session)).isFalse();
        assertThat(store.exists("CASE-OWNER", session)).isTrue();
    }

    @Test
    void tenConcurrentOwnersAndSessionsNeverReadEachOthersState() throws Exception {
        var store = store("validate");
        var executor = Executors.newFixedThreadPool(10);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 10; i++) {
                String user = "parallel-" + i;
                tasks.add(
                        () -> {
                            for (int n = 0; n < 20; n++) {
                                String marker = user + "-" + n;
                                store.save(
                                        user, "shared-session-name", "agent_state", state(marker));
                                assertThat(
                                                store.get(
                                                                user,
                                                                "shared-session-name",
                                                                "agent_state",
                                                                AgentState.class)
                                                        .orElseThrow()
                                                        .getSummary())
                                        .isEqualTo(marker);
                            }
                            return true;
                        });
            }
            for (var future : executor.invokeAll(tasks)) assertThat(future.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void indexedReadWriteMeasurementsWithBackgroundRowsAndDifferentPayloadSizes() throws Exception {
        var store = store("validate");
        try (var connection = source.getConnection();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO dataagent_agent_state(session_id,state_key,state_data)"
                                        + " VALUES (?,'agent_state',?) ON DUPLICATE KEY UPDATE"
                                        + " state_data=VALUES(state_data)")) {
            for (int n = 0; n < 10000; n++) {
                insert.setString(1, "probe-noise:" + n);
                insert.setString(2, "{\"summary\":\"background\"}");
                insert.addBatch();
                if (n % 1000 == 999) insert.executeBatch();
            }
        }
        var metrics = new LinkedHashMap<String, Object>();
        for (int bytes : new int[] {20000, 200000, 2000000}) {
            String text = "x".repeat(bytes);
            var state =
                    AgentState.builder()
                            .userId("probe")
                            .sessionId("payload")
                            .context(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent(text)
                                                    .build()))
                            .build();
            store.save("probe", "payload", "agent_state", state);
            var reads = new ArrayList<Double>();
            var writes = new ArrayList<Double>();
            for (int n = 0; n < 30; n++) {
                long start = System.nanoTime();
                var loaded =
                        store.getVersioned("probe", "payload", "agent_state", AgentState.class);
                reads.add((System.nanoTime() - start) / 1_000_000d);
                assertThat(loaded.value().getContext().get(0).getTextContent()).hasSize(bytes);
                if (n < 10) {
                    start = System.nanoTime();
                    assertThat(
                                    store.saveIfVersion(
                                            "probe",
                                            "payload",
                                            "agent_state",
                                            state,
                                            loaded.version()))
                            .isGreaterThan(0);
                    writes.add((System.nanoTime() - start) / 1_000_000d);
                }
            }
            metrics.put(
                    String.valueOf(bytes), Map.of("read", timing(reads), "write", timing(writes)));
        }
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            try (var rows =
                    statement.executeQuery(
                            "SELECT COUNT(*), SUM(OCTET_LENGTH(state_data)),"
                                + " MAX(OCTET_LENGTH(state_data)) FROM dataagent_agent_state")) {
                rows.next();
                metrics.put(
                        "table",
                        Map.of(
                                "rows",
                                rows.getLong(1),
                                "jsonBytes",
                                rows.getLong(2),
                                "maxSnapshotBytes",
                                rows.getLong(3)));
            }
            try (var rows =
                    statement.executeQuery(
                            "EXPLAIN SELECT state_data, version FROM dataagent_agent_state WHERE"
                                    + " session_id='probe:payload' AND state_key='agent_state' AND"
                                    + " item_index=0")) {
                rows.next();
                metrics.put(
                        "queryPlan",
                        Map.of(
                                "type",
                                rows.getString("type"),
                                "key",
                                rows.getString("key"),
                                "estimatedRows",
                                rows.getLong("rows")));
                assertThat(rows.getString("key")).isEqualTo("PRIMARY");
            }
        }
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target/agent-state-mysql-metrics.json").toFile(), metrics);
    }

    private static Map<String, Object> timing(List<Double> values) {
        Collections.sort(values);
        return Map.of(
                "samples",
                values.size(),
                "p50Ms",
                values.get(values.size() / 2),
                "p95Ms",
                values.get((int) Math.ceil(values.size() * .95) - 1),
                "maxMs",
                values.get(values.size() - 1));
    }

    private static AgentState state(String marker) {
        return AgentState.builder().summary(marker).build();
    }
}
