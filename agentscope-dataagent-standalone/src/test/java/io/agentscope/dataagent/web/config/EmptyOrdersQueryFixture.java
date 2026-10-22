package io.agentscope.dataagent.web.config;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes evaluation SQL on an isolated empty database, preserving real aggregate semantics. */
final class EmptyOrdersQueryFixture {
    private EmptyOrdersQueryFixture() {}

    static List<Map<String, Object>> query(String sql) throws SQLException {
        // Only adapt common MySQL relative-date expressions for this SQLite test fixture.
        // Production SQL and the Wren execution path are never changed.
        String executable =
                sql.replaceAll("(?i)\\bCURDATE\\s*\\(\\s*\\)", "CURRENT_DATE")
                        .replaceAll("(?i)\\bDATE\\s+'(\\d{4}-\\d{2}-\\d{2})'", "'$1'")
                        .replaceAll(
                                "(?i)DATE_SUB\\s*\\(\\s*(CURRENT_DATE|'\\d{4}-\\d{2}-\\d{2}')\\s*,\\s*INTERVAL\\s+'?(\\d+)'?\\s+DAY\\s*\\)",
                                "date($1, '-$2 days')")
                        .replaceAll(
                                "(?i)DATE_ADD\\s*\\(\\s*(CURRENT_DATE|'\\d{4}-\\d{2}-\\d{2}')\\s*,\\s*INTERVAL\\s+'?(\\d+)'?\\s+DAY\\s*\\)",
                                "date($1, '+$2 days')")
                        .replaceAll(
                                "(?i)CURRENT_DATE\\s*-\\s*INTERVAL\\s+'?(\\d+)'?\\s+DAY",
                                "date(CURRENT_DATE, '-$1 days')")
                        .replaceAll(
                                "(?i)CURRENT_DATE\\s*\\+\\s*INTERVAL\\s+'?(\\d+)'?\\s+DAY",
                                "date(CURRENT_DATE, '+$1 days')");
        try (var database = DriverManager.getConnection("jdbc:sqlite::memory:");
                var statement = database.createStatement()) {
            statement.execute("CREATE TABLE orders(order_time TEXT, amount NUMERIC)");
            statement.execute("CREATE TABLE stock_movements(recorded_at TEXT, quantity NUMERIC)");
            statement.execute("CREATE TABLE observations(observed_at TEXT, reading NUMERIC)");
            try (var result = statement.executeQuery(executable)) {
                var columns = result.getMetaData();
                var rows = new ArrayList<Map<String, Object>>();
                while (result.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int column = 1; column <= columns.getColumnCount(); column++)
                        row.put(columns.getColumnLabel(column), result.getObject(column));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
}
