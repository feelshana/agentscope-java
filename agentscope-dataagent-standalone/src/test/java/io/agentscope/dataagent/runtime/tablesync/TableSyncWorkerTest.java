package io.agentscope.dataagent.runtime.tablesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TableSyncWorkerTest {
    @Test
    void passesAllowlistedSpringPropertiesAndPreservesExistingProcessValues() {
        var settings =
                new MockEnvironment()
                        .withProperty("DATAAGENT_TABLE_SYNC_ALLOWED_SOURCES", "127.0.0.1:3306")
                        .withProperty("DATAAGENT_TABLE_SYNC_TARGET_HOST", "127.0.0.1")
                        .withProperty("DATAAGENT_TABLE_SYNC_TARGET_PASSWORD", "from-config")
                        .withProperty("DATAAGENT_TABLE_SYNC_KEY", "spring-only");
        var environment =
                new HashMap<>(
                        Map.of(
                                "PATH", "system-path",
                                "DATAAGENT_DB_PASSWORD", "platform-only",
                                "DATAAGENT_TABLE_SYNC_KEY", "java-only",
                                "DATAAGENT_TABLE_SYNC_TARGET_PASSWORD", "literal-${value}"));
        var worker =
                new TableSyncWorker(
                        new ObjectMapper(),
                        settings,
                        mock(TableSyncTargetStore.class),
                        true,
                        "python",
                        "");

        worker.configureWorkerEnvironment(environment);

        assertThat(environment)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "PATH", "system-path",
                                "DATAAGENT_TABLE_SYNC_ALLOWED_SOURCES", "127.0.0.1:3306",
                                "DATAAGENT_TABLE_SYNC_TARGET_HOST", "127.0.0.1",
                                "DATAAGENT_TABLE_SYNC_TARGET_PASSWORD", "literal-${value}"));
    }

    @Test
    void freshEnvironmentOffersSetupWithoutEnvironmentVariables() {
        var mapper = new ObjectMapper();
        var worker =
                new TableSyncWorker(
                        mapper,
                        new MockEnvironment(),
                        mock(TableSyncTargetStore.class),
                        false,
                        "python",
                        "");
        var result = worker.invoke(TableSyncWorker.Action.LIST, mapper.createObjectNode(), "admin");
        assertThat(result.path("today").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(result.path("tasks").size()).isZero();
        assertThat(result.has("target")).isFalse();
    }
}
