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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Shared H2 fixture for the connector / toolkit tests: recreates the production {@code
 * demo_orders} seed (same statements as {@code data-h2.sql}) against a private in-memory
 * database and exposes a {@link DataSource} pointing at it. Pure JDBC — no Spring context —
 * so the tests double as a syntax check for the seed script itself, under the same
 * {@code MODE=MYSQL} URL shape the production default uses.
 */
final class DemoOrdersFixture {

    static final String JDBC_URL =
            "jdbc:h2:mem:dataagent_demo_orders_fixture;MODE=MYSQL;DB_CLOSE_DELAY=-1";

    private DemoOrdersFixture() {}

    static DataSource demoSource() {
        return new DataSource(
                "demo-db",
                "Demo analytics DB",
                "Embedded demo database with the demo_orders fact table",
                "jdbc",
                null,
                List.of("demo", "h2"),
                Map.of("jdbcUrl", JDBC_URL, "username", "sa", "password", ""));
    }

    /** Idempotent: mirrors the DELETE + INSERT … SYSTEM_RANGE seed from data-h2.sql. */
    static void seedDemoOrders() throws SQLException {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS demo_orders (
                      order_id   INT            NOT NULL,
                      order_date DATE           NOT NULL,
                      region     VARCHAR(16)    NOT NULL,
                      category   VARCHAR(24)    NOT NULL,
                      channel    VARCHAR(12)    NOT NULL,
                      quantity   INT            NOT NULL,
                      amount     DECIMAL(10, 2) NOT NULL,
                      status     VARCHAR(12)    NOT NULL,
                      PRIMARY KEY (order_id)
                    )
                    """);
            stmt.execute("DELETE FROM demo_orders");
            stmt.execute(
                    """
                    INSERT INTO demo_orders
                      (order_id, order_date, region, category, channel, quantity, amount, status)
                    SELECT
                      X,
                      DATEADD('DAY', MOD(X * 61, 365), DATE '2025-01-01'),
                      CASE MOD(X, 4)
                        WHEN 0 THEN 'East' WHEN 1 THEN 'South' WHEN 2 THEN 'West'
                        ELSE 'North' END,
                      CASE MOD(X, 5)
                        WHEN 0 THEN 'Electronics' WHEN 1 THEN 'Apparel' WHEN 2 THEN 'Home'
                        WHEN 3 THEN 'Beauty' ELSE 'Sports' END,
                      CASE MOD(X, 3)
                        WHEN 0 THEN 'web' WHEN 1 THEN 'app' ELSE 'store' END,
                      MOD(X, 9) + 1,
                      CAST(
                        (MOD(X, 9) + 1)
                        * CASE MOD(X, 5)
                            WHEN 0 THEN 420.0 WHEN 1 THEN 89.0 WHEN 2 THEN 156.0
                            WHEN 3 THEN 45.0 ELSE 67.0 END
                        * (0.85 + 0.05 * CAST(MOD(X * 61, 365) / 31 AS INT))
                        * (1.0 + MOD(X * 17, 7) / 100.0)
                        AS DECIMAL(10, 2)),
                      CASE WHEN MOD(X, 25) = 0 THEN 'refunded' ELSE 'completed' END
                    FROM SYSTEM_RANGE(1, 600)
                    """);
        }
    }
}
