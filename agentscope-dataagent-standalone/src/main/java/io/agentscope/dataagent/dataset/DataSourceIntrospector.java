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

import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Read-only JDBC introspection for user-configured external databases: connectivity test and
 * schema / table / column browsing (TC "数据源详情" analogue). All connections are read-only with a
 * short login timeout so a bad host cannot hang the request thread.
 */
@Service
public class DataSourceIntrospector {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_]+$");

    public record ColumnInfo(String name, String type, String description) {}

    public record TableInfo(String name, String type, String comment) {}

    public boolean testConnection(ExternalDataSourceEntity ds) {
        try (Connection c = open(ds)) {
            return c.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    public String connectionError(ExternalDataSourceEntity ds) {
        try (Connection c = open(ds)) {
            if (c.isValid(5)) {
                return null;
            }
            return "connection invalid";
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    public List<String> listSchemas(ExternalDataSourceEntity ds) {
        List<String> out = new ArrayList<>();
        try (Connection c = open(ds)) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getSchemas()) {
                while (rs.next()) {
                    out.add(rs.getString("TABLE_SCHEM"));
                }
            }
            if (out.isEmpty()) {
                try (ResultSet rs = md.getCatalogs()) {
                    while (rs.next()) {
                        out.add(rs.getString("TABLE_CAT"));
                    }
                }
            }
        } catch (SQLException e) {
            throw new DatasetException("Failed to list schemas: " + e.getMessage(), e);
        }
        return out;
    }

    public List<TableInfo> listTables(ExternalDataSourceEntity ds, String schema) {
        List<TableInfo> out = new ArrayList<>();
        try (Connection c = open(ds);
                ResultSet rs =
                        c.getMetaData().getTables(schema, schema, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String remarks = rs.getString("REMARKS");
                out.add(
                        new TableInfo(
                                rs.getString("TABLE_NAME"),
                                rs.getString("TABLE_TYPE"),
                                remarks == null || remarks.isBlank() ? null : remarks));
            }
        } catch (SQLException e) {
            throw new DatasetException("Failed to list tables: " + e.getMessage(), e);
        }
        return out;
    }

    public List<ColumnInfo> listColumns(ExternalDataSourceEntity ds, String schema, String table) {
        List<ColumnInfo> out = new ArrayList<>();
        try (Connection c = open(ds);
                ResultSet rs = c.getMetaData().getColumns(schema, schema, table, "%")) {
            while (rs.next()) {
                String remarks = rs.getString("REMARKS");
                out.add(
                        new ColumnInfo(
                                rs.getString("COLUMN_NAME"),
                                rs.getString("TYPE_NAME"),
                                remarks == null || remarks.isBlank() ? null : remarks));
            }
        } catch (SQLException e) {
            throw new DatasetException("Failed to list columns: " + e.getMessage(), e);
        }
        return out;
    }

    /** COUNT(*) for a table, used to record rowCount on associated datasets. */
    public long countRows(ExternalDataSourceEntity ds, String schema, String table) {
        validateIdentifier(schema, "schema");
        validateIdentifier(table, "table");
        String sql = "SELECT COUNT(*) FROM `" + schema + "`.`" + table + "`";
        if ("postgresql".equalsIgnoreCase(ds.getKind())) {
            sql = "SELECT COUNT(*) FROM \"" + schema + "\".\"" + table + "\"";
        }
        try (Connection c = open(ds);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new DatasetException(
                    "Failed to count rows for " + schema + "." + table + ": " + e.getMessage(), e);
        }
    }

    /** Retrieve the table-level COMMENT (REMARKS) if the database provides one. */
    public String getTableComment(ExternalDataSourceEntity ds, String schema, String table) {
        try (Connection c = open(ds);
                ResultSet rs =
                        c.getMetaData().getTables(schema, schema, table, new String[] {"TABLE"})) {
            if (rs.next()) {
                String remarks = rs.getString("REMARKS");
                return (remarks == null || remarks.isBlank()) ? null : remarks;
            }
        } catch (SQLException e) {
            // ignore — table comment is optional
        }
        return null;
    }

    private Connection open(ExternalDataSourceEntity ds) throws SQLException {
        DriverManager.setLoginTimeout(10);
        Connection c =
                DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword());
        c.setReadOnly(true);
        return c;
    }

    private static void validateIdentifier(String name, String label) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("非法" + label + "标识符: " + name);
        }
    }
}
