package io.agentscope.dataagent.web.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Selects existing framework stores without duplicating their read/write contracts. */
@Configuration(proxyBeanMethods = false)
public class AgentStateConfig {
    private static final Logger log = LoggerFactory.getLogger(AgentStateConfig.class);
    static final String TABLE = "dataagent_agent_state";

    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    public AgentStateStore durableAgentStateStore(DataSource dataSource, Environment environment)
            throws Exception {
        String product;
        String database;
        String url;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
            database = connection.getCatalog();
            url = connection.getMetaData().getURL();
        }
        if ("H2".equalsIgnoreCase(product)) {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(url.getBytes(StandardCharsets.UTF_8));
            Path directory =
                    Path.of(
                            environment.getProperty("user.home", System.getProperty("user.home")),
                            ".agentscope-dataagent",
                            "state",
                            "h2-" + HexFormat.of().formatHex(hash, 0, 12));
            log.info("Agent state: durable JSON files for the H2 development database");
            return new JsonFileAgentStateStore(directory);
        }
        if (!"MySQL".equalsIgnoreCase(product)) {
            throw new IllegalStateException(
                    "Unsupported platform database for agent state: "
                            + product
                            + "; provide an explicit AgentStateStore bean");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalStateException("Agent state requires a selected platform database");
        }
        String ddl =
                environment
                        .getProperty("spring.jpa.hibernate.ddl-auto", "none")
                        .toLowerCase(Locale.ROOT);
        if (Set.of("update", "create", "create-drop").contains(ddl)) {
            new ResourceDatabasePopulator(new ClassPathResource("db/agent-state-mysql.sql"))
                    .execute(dataSource);
        }
        validateSchema(dataSource);
        log.info("Agent state: framework MySQL snapshots in platform table {}", TABLE);
        return new MysqlAgentStateStore(dataSource, database, TABLE, false);
    }

    /** Fail before the framework constructor can migrate an incomplete production table. */
    private static void validateSchema(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            try (var ignored =
                    statement.executeQuery(
                            "SELECT session_id, state_key, item_index, state_data, version,"
                                    + " created_at, updated_at FROM "
                                    + TABLE
                                    + " WHERE 1=0")) {
                // Resolve every required column without scanning stored payloads.
            }
            try (var query =
                    connection.prepareStatement(
                            "SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                                    + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
                                    + " AND COLUMN_NAME IN ('session_id', 'state_key')")) {
                query.setString(1, connection.getCatalog());
                query.setString(2, TABLE);
                try (var rows = query.executeQuery()) {
                    int columns = 0;
                    while (rows.next()) {
                        String collation = rows.getString(1);
                        if (collation == null || !collation.endsWith("_bin")) {
                            throw new IllegalStateException(
                                    "Agent state namespace columns require binary collation;"
                                            + " apply migration 0084 before startup");
                        }
                        columns++;
                    }
                    if (columns != 2) {
                        throw new IllegalStateException(
                                "Agent state namespace columns are missing");
                    }
                }
            }
        }
    }
}
