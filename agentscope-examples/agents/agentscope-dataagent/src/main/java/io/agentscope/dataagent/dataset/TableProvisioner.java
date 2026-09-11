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
package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the write-side DDL/DML against the configured dataset database (e.g. MySQL {@code
 * data_agent}): every uploaded dataset becomes one prefixed table {@code
 * ds_<owner8>_<dataset8>_<name>} inside that single database, so the data lands exactly where the
 * operator configured it and cross-dataset joins are plain same-db joins.
 *
 * <p>Tenant isolation is enforced at two layers: the registry only exposes a owner's datasets to
 * that owner, and {@code DataAgentToolkit} rejects SQL referencing another owner's {@code ds_*}
 * table. Read-side querying is left to {@code JdbcSqlConnector}, which connects read-only to the
 * same configured jdbcUrl.
 *
 * <p>Every identifier that reaches a statement is produced by {@link Identifiers#sanitize} /
 * {@link #buildTableName} and is backtick-quoted, so no user-controlled text can escape into SQL.
 */
@Service
public class TableProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TableProvisioner.class);
    private static final int BATCH = 500;
    private static final int MAX_TABLE_LEN = 64;

    private static final List<DateTimeFormatter> DATES =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                    DateTimeFormatter.ofPattern("yyyy-M-d"));
    private static final List<DateTimeFormatter> DATETIMES =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    private final DatasetStoreProperties props;

    public TableProvisioner(DatasetStoreProperties props) {
        this.props = props;
    }

    /** The database name segment of the configured url, for metadata display. */
    public String databaseName() {
        String base = props.url();
        int qi = base.indexOf('?');
        String head = qi >= 0 ? base.substring(0, qi) : base;
        int schemeEnd = head.indexOf("//");
        int slash = schemeEnd >= 0 ? head.indexOf('/', schemeEnd + 2) : head.indexOf('/');
        return slash >= 0 ? head.substring(slash + 1) : "";
    }

    /**
     * Deterministic, collision-resistant table name inside the configured database: {@code
     * ds_<owner8>_<dataset8>_<sanitised name>}, bounded to MySQL's 64-char identifier limit. The
     * owner prefix doubles as the tenant marker the SQL guard checks.
     */
    public static String buildTableName(String ownerId, String datasetId, String name) {
        String owner8 = Identifiers.shortHash(ownerId == null ? "" : ownerId);
        String ds8 = Identifiers.shortHash(datasetId == null ? "" : datasetId.replace("-", ""));
        String prefix = "ds_" + owner8 + "_" + ds8 + "_";
        int room = MAX_TABLE_LEN - prefix.length();
        String base = Identifiers.sanitize(name, "data");
        if (base.length() > room) {
            base = base.substring(0, Math.max(room, 1));
        }
        return prefix + base;
    }

    public void createTable(String table, List<ColumnSchema> columns) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE IF NOT EXISTS `").append(table).append("` (");
        for (int i = 0; i < columns.size(); i++) {
            ColumnSchema col = columns.get(i);
            ddl.append('`').append(col.name()).append("` ").append(col.sqlType());
            ddl.append(col.nullable() ? " NULL" : " NOT NULL");
            if (i < columns.size() - 1) {
                ddl.append(", ");
            }
        }
        ddl.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        try (Connection c = connect();
                Statement st = c.createStatement()) {
            st.execute(ddl.toString());
        } catch (SQLException e) {
            throw new DatasetException(
                    "Failed to create table "
                            + databaseName()
                            + "."
                            + table
                            + ": "
                            + e.getMessage(),
                    e);
        }
    }

    public void bulkInsert(String table, List<ColumnSchema> columns, List<List<String>> rows) {
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        String cols = String.join(", ", columns.stream().map(c -> "`" + c.name() + "`").toList());
        String sql = "INSERT INTO `" + table + "` (" + cols + ") VALUES (" + placeholders + ")";
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int count = 0;
                for (List<String> row : rows) {
                    for (int i = 0; i < columns.size(); i++) {
                        String v = i < row.size() ? row.get(i) : null;
                        ps.setObject(i + 1, convert(columns.get(i).sqlType(), v));
                    }
                    ps.addBatch();
                    if (++count % BATCH == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatasetException(
                    "Failed to insert rows into "
                            + databaseName()
                            + "."
                            + table
                            + ": "
                            + e.getMessage(),
                    e);
        }
    }

    public void dropTable(String table) {
        String sql = "DROP TABLE IF EXISTS `" + table + "`";
        try (Connection c = connect();
                Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            log.warn("Failed to drop dataset table {}: {}", table, e.getMessage());
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(props.url(), props.username(), props.password());
    }

    private Object convert(String sqlType, String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            switch (sqlType) {
                case "BIGINT":
                    return Long.parseLong(v.trim());
                case "DECIMAL(18,6)":
                    return new BigDecimal(v.trim());
                case "DATE":
                    return java.sql.Date.valueOf(parseDate(v));
                case "DATETIME":
                    return java.sql.Timestamp.valueOf(parseDateTime(v));
                default:
                    return v;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LocalDate parseDate(String v) {
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(v.trim(), f);
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        throw new IllegalArgumentException("unparseable date: " + v);
    }

    private LocalDateTime parseDateTime(String v) {
        for (DateTimeFormatter f : DATETIMES) {
            try {
                return LocalDateTime.parse(v.trim(), f);
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        // a bare date is a valid datetime at midnight
        return parseDate(v).atStartOfDay();
    }
}
