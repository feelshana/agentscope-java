package io.agentscope.dataagent.runtime.tablesync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.dataagent.runtime.tablesync.TableSyncWorker.Failure;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Stores the administrator's single target connection in the platform database. */
@Component
public class TableSyncTargetStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TableSyncTargetStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    ObjectNode read() {
        try {
            return jdbc.query(
                    "SELECT host, port, username, password, ssl_mode FROM"
                            + " dataagent_table_sync_target WHERE id=1",
                    result -> {
                        if (!result.next()) return null;
                        return mapper.createObjectNode()
                                .put("host", result.getString("host"))
                                .put("port", result.getInt("port"))
                                .put("username", result.getString("username"))
                                .put("password", result.getString("password"))
                                .put("sslMode", result.getString("ssl_mode"));
                    });
        } catch (BadSqlGrammarException error) {
            var cause = error.getSQLException();
            // MySQL and the local H2 profile both report a distinct missing-table state.
            if (cause != null
                    && ("42S02".equals(cause.getSQLState()) || cause.getErrorCode() == 42104))
                return null;
            throw new Failure("TARGET_SETTINGS_ERROR");
        } catch (DataAccessException error) {
            throw new Failure("TARGET_SETTINGS_ERROR");
        }
    }

    void save(ObjectNode target) {
        try {
            String host = target.path("host").asText();
            int port = target.path("port").asInt();
            String username = target.path("username").asText();
            String password = target.path("password").asText();
            String sslMode = target.path("sslMode").asText();
            try {
                jdbc.update(
                        """
                        INSERT INTO dataagent_table_sync_target
                            (id, host, port, username, password, ssl_mode)
                        VALUES (1, ?, ?, ?, ?, ?)
                        """,
                        host,
                        port,
                        username,
                        password,
                        sslMode);
            } catch (DuplicateKeyException error) {
                ObjectNode current = read();
                if (current == null
                        || !host.equals(current.path("host").asText())
                        || port != current.path("port").asInt()) {
                    throw new Failure("TARGET_ENDPOINT_LOCKED");
                }
                jdbc.update(
                        """
                        UPDATE dataagent_table_sync_target
                        SET username=?, password=?, ssl_mode=?
                        WHERE id=1 AND host=? AND port=?
                        """,
                        username,
                        password,
                        sslMode,
                        host,
                        port);
            }
        } catch (BadSqlGrammarException error) {
            var cause = error.getSQLException();
            if (cause != null
                    && ("42S02".equals(cause.getSQLState()) || cause.getErrorCode() == 42104))
                throw new Failure("MIGRATION_REQUIRED");
            throw new Failure("TARGET_SETTINGS_ERROR");
        } catch (DataAccessException error) {
            throw new Failure("TARGET_SETTINGS_ERROR");
        }
    }

    String platformSchema() {
        try {
            String schema =
                    jdbc.execute(
                            (ConnectionCallback<String>)
                                    connection -> {
                                        String catalog = connection.getCatalog();
                                        if (catalog != null && !catalog.isBlank()) return catalog;
                                        return connection.getSchema();
                                    });
            if (schema == null || schema.isBlank()) throw new Failure("TARGET_SETTINGS_ERROR");
            return schema;
        } catch (DataAccessException error) {
            throw new Failure("TARGET_SETTINGS_ERROR");
        }
    }
}
