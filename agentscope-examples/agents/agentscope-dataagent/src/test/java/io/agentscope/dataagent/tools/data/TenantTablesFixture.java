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
 * Shared H2 fixture for the connector / toolkit tests: seeds two analytics
 * tables ({@code tenant_storage_utilization} and {@code project_info}) against
 * a private in-memory H2 database. Pure JDBC — no Spring context.
 *
 * <p>Mirrors the production MySQL seed in {@code mysql-schema.sql} so tests
 * double as a syntax check for the DDL and DML themselves.
 */
final class TenantTablesFixture {

    static final String JDBC_URL =
            "jdbc:h2:mem:dataagent_tenant_fixture;MODE=MYSQL;DB_CLOSE_DELAY=-1";

    private TenantTablesFixture() {}

    static DataSource demoSource() {
        return new DataSource(
                "demo-db",
                "Demo analytics DB",
                "Embedded demo database with tenant_storage_utilization and "
                        + "project_info tables for storage-optimization analysis",
                "jdbc",
                null,
                List.of("demo", "h2"),
                Map.of("jdbcUrl", JDBC_URL, "username", "sa", "password", ""));
    }

    /** Idempotent: drops and re-creates both tables with the seed data. */
    static void seedTenantTables() throws SQLException {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement stmt = conn.createStatement()) {

            // ── DDL ──────────────────────────────────────────────
            stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tenant_storage_utilization (
                      id                    INT            NOT NULL AUTO_INCREMENT,
                      project_name          VARCHAR(64)    NOT NULL,
                      db_name               VARCHAR(64)    NOT NULL,
                      table_name            VARCHAR(64)    NOT NULL,
                      size_gb               DECIMAL(10,2)  NOT NULL,
                      file_count            INT            NOT NULL,
                      dir_path              VARCHAR(256)   NOT NULL,
                      uncompressed_size_gb  DECIMAL(10,2)  NOT NULL,
                      small_file_size_gb    DECIMAL(10,2)  NOT NULL,
                      expired_file_size_gb  DECIMAL(10,2)  NOT NULL,
                      expired_file_count    INT            NOT NULL,
                      PRIMARY KEY (id)
                    )
                    """);
            stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS project_info (
                      id            INT           NOT NULL AUTO_INCREMENT,
                      project_name  VARCHAR(64)   NOT NULL,
                      owner_name    VARCHAR(32)   NOT NULL,
                      PRIMARY KEY (id)
                    )
                    """);
            stmt.execute("DELETE FROM tenant_storage_utilization");
            stmt.execute("DELETE FROM project_info");

            // ── project_info seed (10 tenants) ───────────────────
            stmt.execute(
                    """
                    INSERT INTO project_info (id, project_name, owner_name) VALUES
                      (1,  'data-lake',        'Alice'),
                      (2,  'user-profile',     'Bob'),
                      (3,  'ml-pipeline',      'Carol'),
                      (4,  'log-analysis',     'Dave'),
                      (5,  'order-system',     'Eve'),
                      (6,  'search-engine',    'Frank'),
                      (7,  'recommendation',   'Grace'),
                      (8,  'risk-control',     'Helen'),
                      (9,  'payment-gateway',  'Ivan'),
                      (10, 'content-platform', 'Judy')
                    """);

            // ── tenant_storage_utilization seed (33 rows) ────────
            stmt.execute(
                    """
                    INSERT INTO tenant_storage_utilization
                      (project_name, db_name, table_name, size_gb, file_count, dir_path,
                       uncompressed_size_gb, small_file_size_gb, expired_file_size_gb, expired_file_count)
                    VALUES
                      ('data-lake', 'lake_prod', 'raw_events',    200.00, 15000, '/data/lake/raw_events',    180.00, 45.00,  80.00, 12000),
                      ('data-lake', 'lake_prod', 'click_stream',  150.00, 12000, '/data/lake/click_stream',  135.00, 30.00,  50.00,  8000),
                      ('data-lake', 'lake_prod', 'user_actions',  150.00,  8000, '/data/lake/user_actions',  130.00, 15.00,  25.00, 11000),
                      ('user-profile', 'profile_db', 'user_base',     80.00,  5000, '/data/profile/user_base',     72.00, 20.00, 15.00,  8000),
                      ('user-profile', 'profile_db', 'user_tags',     60.00,  8000, '/data/profile/user_tags',     54.00, 25.00, 10.00, 10000),
                      ('user-profile', 'profile_db', 'user_behavior',125.00, 10000, '/data/profile/user_behavior',110.00, 18.00, 22.00,  8000),
                      ('ml-pipeline', 'ml_db', 'feature_store',  200.00,  6000, '/data/ml/feature_store',  180.00, 40.00,  75.00,  5000),
                      ('ml-pipeline', 'ml_db', 'model_logs',      80.00,  4000, '/data/ml/model_logs',      72.00, 15.00,  35.00,  3000),
                      ('ml-pipeline', 'ml_db', 'training_data',   35.00,  2000, '/data/ml/training_data',   30.00,  5.00,  12.00,  1000),
                      ('log-analysis', 'log_db', 'access_log',  100.00,  8000, '/data/log/access_log',  90.00, 25.00, 20.00, 5000),
                      ('log-analysis', 'log_db', 'error_log',    20.00,  1500, '/data/log/error_log',   18.00,  3.00,  2.00,  800),
                      ('log-analysis', 'log_db', 'audit_log',    50.00,  3000, '/data/log/audit_log',   45.00, 10.00,  8.00, 2000),
                      ('order-system', 'order_db', 'orders',       80.00, 3000, '/data/order/orders',       72.00, 10.00,  8.00, 2000),
                      ('order-system', 'order_db', 'order_items',  60.00, 2500, '/data/order/order_items',  54.00,  8.00,  5.00, 1500),
                      ('order-system', 'order_db', 'payments',     35.00, 1800, '/data/order/payments',     30.00,  5.00,  3.00, 1000),
                      ('search-engine', 'search_db', 'index_data',  100.00, 80000, '/data/search/index_data',  90.00, 30.00,  5.00,  500),
                      ('search-engine', 'search_db', 'query_log',    50.00, 40000, '/data/search/query_log',   45.00, 15.00,  2.00,  300),
                      ('recommendation', 'rec_db', 'user_vectors',  50.00, 2000, '/data/rec/user_vectors',  45.00,  2.00,  1.00,  200),
                      ('recommendation', 'rec_db', 'item_vectors',  50.00, 2000, '/data/rec/item_vectors',  45.00,  3.00,  0.50,  100),
                      ('recommendation', 'rec_db', 'model_params',  20.00,  500, '/data/rec/model_params',  18.00,  5.00,  0.50,   50),
                      ('risk-control', 'risk_db', 'risk_events',  50.00, 3000, '/data/risk/risk_events',  45.00,  5.00,  2.00,  300),
                      ('risk-control', 'risk_db', 'blacklist',     1.00,  200, '/data/risk/blacklist',     0.90,  0.10,  0.05,   20),
                      ('risk-control', 'risk_db', 'rules',         0.50,  100, '/data/risk/rules',         0.45,  0.05,  0.02,   10),
                      ('risk-control', 'risk_db', 'alerts',       35.00, 2500, '/data/risk/alerts',       30.00,  6.00,  3.00,  500),
                      ('payment-gateway', 'pay_db', 'transactions', 60.00, 4000, '/data/pay/transactions', 54.00,  5.00,  3.00, 1000),
                      ('payment-gateway', 'pay_db', 'refunds',      10.00,  800, '/data/pay/refunds',       9.00,  1.00,  0.50,  200),
                      ('payment-gateway', 'pay_db', 'settlements',   6.00,  500, '/data/pay/settlements',   5.40,  0.50,  0.30,  100),
                      ('content-platform', 'content_db', 'articles',   40.00, 3000, '/data/content/articles',   36.00,  5.00,  3.00,  800),
                      ('content-platform', 'content_db', 'comments',   30.00, 5000, '/data/content/comments',   27.00,  8.00,  2.00, 1000),
                      ('content-platform', 'content_db', 'media_refs', 36.00, 2000, '/data/content/media_refs', 32.00,  4.00,  8.00, 2500)
                    """);
        }
    }
}
