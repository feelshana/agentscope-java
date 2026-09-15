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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
        ddl.append(")").append(engineSuffix());
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

    /**
     * Probe the column metadata of a derived SQL without materialising the table: wraps the SQL
     * in {@code SELECT * FROM (<sql>) _probe LIMIT 0} so no rows are fetched. Returns the
     * detected columns as {@link ColumnSchema} records with {@code nullable=true} (derived
     * columns are always considered nullable by default).
     */
    public List<ColumnSchema> probeColumns(String sql) {
        String probeSql = "SELECT * FROM (\n" + sql.trim() + "\n) AS _probe LIMIT 0";
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement(probeSql);
                java.sql.ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<ColumnSchema> cols = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                String name = md.getColumnLabel(i);
                String type = md.getColumnTypeName(i);
                int precision = md.getPrecision(i);
                int scale = md.getScale(i);
                String sqlType = toMysqlType(type, precision, scale);
                cols.add(new ColumnSchema(name, name, sqlType, true, null));
            }
            return cols;
        } catch (SQLException e) {
            throw new DatasetException("Failed to probe derived SQL columns: " + e.getMessage(), e);
        }
    }

    /**
     * Materialise a derived table from a SELECT statement. Uses explicit CREATE TABLE (column
     * types derived from {@link #probeColumns}) followed by INSERT INTO ... SELECT, avoiding
     * MySQL's CTAS type-inheritance quirks.
     */
    public void createDerivedTable(String table, String sql) {
        List<ColumnSchema> cols = probeColumns(sql);
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE `").append(table).append("` (");
        for (int i = 0; i < cols.size(); i++) {
            ColumnSchema c = cols.get(i);
            ddl.append('`').append(c.name()).append("` ").append(c.sqlType()).append(" NULL");
            if (i < cols.size() - 1) {
                ddl.append(", ");
            }
        }
        ddl.append(")").append(engineSuffix());
        String insertSql =
                "INSERT INTO `" + table + "` SELECT * FROM (\n" + sql.trim() + "\n) AS _src";
        try (Connection c = connect();
                Statement st = c.createStatement()) {
            st.execute(ddl.toString());
            st.execute(insertSql);
        } catch (SQLException e) {
            dropTable(table);
            throw new DatasetException(
                    "Failed to create derived table " + table + ": " + e.getMessage(), e);
        }
    }

    /** Row count for a table already in the dataset store. */
    public long countRows(String table) {
        String sql = "SELECT COUNT(*) FROM `" + table + "`";
        try (Connection c = connect();
                Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            log.warn("countRows({}) failed: {}", table, e.getMessage());
            return 0;
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

    /**
     * Engine suffix for CREATE TABLE DDL. H2 (used in tests) does not accept ENGINE=InnoDB;
     * MySQL does. Explicit COLLATE ensures batch tables and derived tables share the same
     * collation so that UNION / JOIN operations don't hit "Illegal mix of collations".
     */
    private String engineSuffix() {
        return props.url().contains(":h2:")
                ? ""
                : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    /**
     * Map a JDBC column type name + precision/scale to a MySQL DDL type string.
     * Handles common MySQL 8 and H2 type names.
     */
    private static String toMysqlType(String jdbcType, int precision, int scale) {
        if (jdbcType == null) {
            return "VARCHAR(255)";
        }
        String upper = jdbcType.toUpperCase();
        return switch (upper) {
            case "BIGINT", "INT8" -> "BIGINT";
            case "INTEGER", "INT", "INT4", "MEDIUMINT" -> "INT";
            case "SMALLINT", "INT2" -> "SMALLINT";
            case "TINYINT" -> precision == 1 ? "TINYINT(1)" : "TINYINT";
            case "DECIMAL", "NUMERIC", "NUMBER" ->
                    "DECIMAL(" + Math.max(precision, 18) + "," + Math.max(scale, 6) + ")";
            case "DOUBLE", "FLOAT", "FLOAT8" -> "DOUBLE";
            case "REAL", "FLOAT4" -> "FLOAT";
            case "DATE" -> "DATE";
            case "TIMESTAMP", "DATETIME", "TIMESTAMP WITHOUT TIME ZONE" -> "DATETIME";
            case "TIME" -> "TIME";
            case "BOOLEAN", "BOOL" -> "TINYINT(1)";
            case "TEXT", "LONGTEXT", "MEDIUMTEXT", "CLOB" -> "TEXT";
            default -> {
                if (upper.startsWith("VARCHAR") || upper.startsWith("CHARACTER VARYING")) {
                    yield precision > 0 ? "VARCHAR(" + precision + ")" : "VARCHAR(255)";
                }
                if (upper.startsWith("CHAR")) {
                    yield precision > 0 ? "CHAR(" + precision + ")" : "CHAR(1)";
                }
                yield "VARCHAR(255)";
            }
        };
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
