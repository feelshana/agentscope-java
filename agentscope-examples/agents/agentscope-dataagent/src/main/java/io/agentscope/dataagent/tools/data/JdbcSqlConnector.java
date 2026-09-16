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
package io.agentscope.dataagent.tools.data;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generic JDBC {@link SqlConnector}. Serves sources whose {@code kind} is {@code jdbc} and whose
 * {@code properties} carry {@code jdbcUrl} (optionally {@code username} / {@code password}).
 *
 * <p>Safety rails: connections are opened read-only with a 10 s query timeout; table names are
 * validated against a strict identifier pattern before being interpolated into the COUNT/sample
 * statements (the SELECT-only gate for {@code run_sql_preview} lives in {@link DataAgentToolkit});
 * preview row counts are clamped to a hard cap of 100.
 */
public final class JdbcSqlConnector implements SqlConnector {

    static final int DEFAULT_ROW_LIMIT = 20;
    static final int MAX_ROW_LIMIT = 100;
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int CELL_TRUNCATION = 200;

    /**
     * {@code table} or {@code schema.table} with plain identifiers only — the only values ever
     * interpolated into the COUNT(*) / sample statements built by {@link #describeTable}.
     */
    private static final Pattern TABLE_NAME = Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?$");

    @Override
    public boolean supports(DataSource source) {
        if (source == null) {
            return false;
        }
        if (!"jdbc".equalsIgnoreCase(source.kind())) {
            return false;
        }
        String url = source.properties().get("jdbcUrl");
        return url != null && !url.isBlank();
    }

    @Override
    public String describeTable(DataSource source, String table) {
        if (table == null || !TABLE_NAME.matcher(table.trim()).matches()) {
            return "error: table must be a plain identifier (optionally schema.table), got: "
                    + table;
        }
        String name = table.trim();
        String[] parts = name.split("\\.");
        String schema = parts.length == 2 ? parts[0] : null;
        String tableName = parts.length == 2 ? parts[1] : name;

        List<String[]> columns = new ArrayList<>();
        try (Connection conn = open(source)) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String variant : tableCaseVariants(tableName)) {
                try (ResultSet rs = meta.getColumns(null, schema, variant, null)) {
                    columns.addAll(readColumns(rs));
                }
                if (!columns.isEmpty()) {
                    break;
                }
            }
            if (columns.isEmpty()) {
                return "error: table '" + table + "' not found in source '" + source.id() + "'";
            }
            String count = queryScalar(conn, "SELECT COUNT(*) FROM " + name);
            StringBuilder sb = new StringBuilder();
            sb.append("Table `")
                    .append(name)
                    .append("` — ")
                    .append(columns.size())
                    .append(" columns, ")
                    .append(count)
                    .append(" rows\n\n");
            sb.append("| column | type |\n|---|---|\n");
            for (String[] col : columns) {
                sb.append("| ").append(col[0]).append(" | ").append(col[1]).append(" |\n");
            }
            sb.append("\nSample (first 5 rows):\n\n");
            sb.append(previewQuery(conn, "SELECT * FROM " + name, null, 5));
            return sb.toString();
        } catch (SQLTimeoutException e) {
            return "error: query timed out after " + QUERY_TIMEOUT_SECONDS + "s: " + e.getMessage();
        } catch (SQLException e) {
            return "error: " + e.getMessage();
        }
    }

    @Override
    public String runSqlPreview(DataSource source, String sql, String question, int rowLimit) {
        if (sql == null || sql.isBlank()) {
            return "error: sql must not be blank";
        }
        int limit = normalizeRowLimit(rowLimit);
        try (Connection conn = open(source)) {
            return previewQuery(conn, sql, question, limit);
        } catch (SQLTimeoutException e) {
            return "error: query timed out after " + QUERY_TIMEOUT_SECONDS + "s: " + e.getMessage();
        } catch (SQLException e) {
            return "error: " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private static Connection open(DataSource source) throws SQLException {
        Map<String, String> props = source.properties();
        String url = props.get("jdbcUrl");
        String user = props.get("username");
        String password = props.get("password");
        Connection conn =
                (user != null && !user.isBlank())
                        ? DriverManager.getConnection(url, user, password)
                        : DriverManager.getConnection(url);
        try {
            conn.setReadOnly(true);
            return conn;
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
    }

    private static int normalizeRowLimit(int rowLimit) {
        if (rowLimit <= 0) {
            return DEFAULT_ROW_LIMIT;
        }
        return Math.min(rowLimit, MAX_ROW_LIMIT);
    }

    /**
     * Runs the statement and renders up to {@code maxRows} rows as a markdown report. The report
     * begins with the natural-language question (when provided) and the SQL statement that was
     * executed, then a result table. Fetches one extra row so truncation can be reported instead
     * of silently dropping data.
     */
    private static String previewQuery(Connection conn, String sql, String question, int maxRows)
            throws SQLException {
        SqlPreviewResult result = readPreview(conn, sql, maxRows);
        StringBuilder sb = new StringBuilder();
        if (question != null && !question.isBlank()) {
            sb.append("**查询问题：** ").append(question.trim()).append("\n\n");
        }
        sb.append("**SQL 语句：**\n```sql\n").append(sql).append("\n```\n\n");
        sb.append("**查询结果：**\n\n| ");
        sb.append(
                String.join(
                        " | ",
                        result.columns().stream().map(JdbcSqlConnector::truncateCell).toList()));
        sb.append(" |\n|").append("---|".repeat(result.columns().size())).append("\n");
        for (var row : result.rows()) {
            sb.append("| ")
                    .append(
                            String.join(
                                    " | ",
                                    row.stream().map(JdbcSqlConnector::truncateCell).toList()))
                    .append(" |\n");
        }
        if (result.rows().isEmpty()) sb.append("*(0 rows returned)*\n");
        if (result.truncated())
            sb.append("\n(preview truncated: ").append(result.truncationReason()).append(")");
        return sb.toString();
    }

    @Override
    public SqlPreviewResult query(DataSource source, String sql, int rowLimit) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        try (Connection conn = open(source)) {
            return readPreview(conn, sql, normalizeRowLimit(rowLimit));
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "数据库执行失败 [" + e.getSQLState() + "]: " + e.getMessage(), e);
        }
    }

    private static SqlPreviewResult readPreview(Connection conn, String sql, int maxRows)
            throws SQLException {
        long start = System.nanoTime();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setMaxRows(maxRows + 1);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                if (colCount > 100) throw new IllegalArgumentException("结果超过 100 列，请缩小投影范围");
                List<String> headers = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) headers.add(meta.getColumnLabel(i));
                List<List<String>> rows = new ArrayList<>();
                java.util.Set<String> reasons = new java.util.LinkedHashSet<>();
                int budget = mapper.writeValueAsString(headers).length() + 512;
                if (budget > 20000) throw new IllegalArgumentException("结果列名过长，请使用简短别名");
                while (rs.next()) {
                    if (rows.size() >= maxRows) {
                        reasons.add("row_limit");
                        break;
                    }
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        String value = rs.getString(i);
                        if (value != null && value.length() > CELL_TRUNCATION) {
                            value = value.substring(0, CELL_TRUNCATION) + "…";
                            reasons.add("cell_limit");
                        }
                        row.add(value);
                    }
                    int size = mapper.writeValueAsString(row).length() + 1;
                    if (budget + size > 20000) {
                        reasons.add("payload_limit");
                        break;
                    }
                    budget += size;
                    rows.add(java.util.Collections.unmodifiableList(row));
                }
                return new SqlPreviewResult(
                        List.copyOf(headers),
                        List.copyOf(rows),
                        rows.size(),
                        !reasons.isEmpty(),
                        String.join(",", reasons),
                        (System.nanoTime() - start) / 1_000_000);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("结果无法序列化", e);
            }
        }
    }

    private static String queryScalar(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "?";
        }
    }

    private static List<String[]> readColumns(ResultSet rs) throws SQLException {
        Map<String, String> byName = new LinkedHashMap<>();
        while (rs.next()) {
            String tableSchema = rs.getString("TABLE_SCHEM");
            if (tableSchema != null && tableSchema.equalsIgnoreCase("INFORMATION_SCHEMA")) {
                continue;
            }
            byName.putIfAbsent(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : byName.entrySet()) {
            out.add(new String[] {e.getKey(), e.getValue()});
        }
        return out;
    }

    private static List<String> tableCaseVariants(String tableName) {
        List<String> variants = new ArrayList<>();
        variants.add(tableName);
        if (!tableName.equals(tableName.toUpperCase())) {
            variants.add(tableName.toUpperCase());
        }
        if (!tableName.equals(tableName.toLowerCase())) {
            variants.add(tableName.toLowerCase());
        }
        return variants;
    }

    private static String truncateCell(String value) {
        if (value == null) {
            return "NULL";
        }
        String flat = value.replace("\r", " ").replace("\n", " ").replace("|", "\\|");
        return flat.length() <= CELL_TRUNCATION ? flat : flat.substring(0, CELL_TRUNCATION) + "…";
    }
}
