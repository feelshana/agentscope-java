package io.agentscope.dataagent.runtime.tablesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class TableSyncTargetStoreTest {
    @Test
    void storesPlaintextRotatesCredentialsAndPreventsQueueRedirection() {
        var database =
                new EmbeddedDatabaseBuilder()
                        .generateUniqueName(true)
                        .setType(EmbeddedDatabaseType.H2)
                        .build();
        try {
            var mapper = new ObjectMapper();
            var jdbc = new JdbcTemplate(database);
            var store = new TableSyncTargetStore(jdbc, mapper);
            assertThat(store.read()).isNull();
            var connection =
                    mapper.createObjectNode()
                            .put("host", "127.0.0.1")
                            .put("port", 3306)
                            .put("username", "writer")
                            .put("password", "credential")
                            .put("sslMode", "REQUIRED");
            assertThatThrownBy(() -> store.save(connection))
                    .isInstanceOf(TableSyncWorker.Failure.class)
                    .hasMessage("MIGRATION_REQUIRED");
            assertThat(store.read()).isNull();
            jdbc.execute(
                    """
                    CREATE TABLE dataagent_table_sync_target (
                        id INT PRIMARY KEY, host VARCHAR(253), port INT, username VARCHAR(128),
                        password VARCHAR(1024), ssl_mode VARCHAR(16)
                    )
                    """);
            store.save(connection);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT password FROM dataagent_table_sync_target",
                                    String.class))
                    .isEqualTo("credential");

            store.save(connection.deepCopy().put("password", "replacement"));
            assertThat(new TableSyncTargetStore(jdbc, mapper).read().path("password").asText())
                    .isEqualTo("replacement");
            assertThatThrownBy(() -> store.save(connection.deepCopy().put("port", 3307)))
                    .isInstanceOf(TableSyncWorker.Failure.class)
                    .hasMessage("TARGET_ENDPOINT_LOCKED");
        } finally {
            database.shutdown();
        }
    }
}
