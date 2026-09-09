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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link JdbcSqlConnector} against a real H2 database seeded with the production
 * {@code demo_orders} data (see {@link DemoOrdersFixture}). No Spring context — the connector
 * is plain JDBC.
 */
class JdbcSqlConnectorTest {

    private static final JdbcSqlConnector CONNECTOR = new JdbcSqlConnector();

    private static DataSource source;

    @BeforeAll
    static void seed() throws Exception {
        DemoOrdersFixture.seedDemoOrders();
        source = DemoOrdersFixture.demoSource();
    }

    @Test
    void supportsOnlyJdbcSourcesWithUrl() {
        assertThat(CONNECTOR.supports(source)).isTrue();
        assertThat(CONNECTOR.supports(null)).isFalse();
        assertThat(
                        CONNECTOR.supports(
                                new DataSource(
                                        "no-url", "No url", null, "jdbc", null, List.of(),
                                        Map.of())))
                .as("jdbc kind without jdbcUrl must not be supported")
                .isFalse();
        assertThat(
                        CONNECTOR.supports(
                                new DataSource(
                                        "api",
                                        "HTTP API",
                                        null,
                                        "http",
                                        null,
                                        List.of(),
                                        Map.of("jdbcUrl", "jdbc:h2:mem:irrelevant"))))
                .as("non-jdbc kind must not be supported")
                .isFalse();
    }

    @Test
    void describeTableReportsColumnsRowsAndSample() {
        String out = CONNECTOR.describeTable(source, "demo_orders");

        assertThat(out).doesNotStartWith("error");
        assertThat(out).contains("8 columns");
        assertThat(out).contains("600 rows");
        assertThat(out).containsIgnoringCase("order_id");
        assertThat(out).containsIgnoringCase("amount");
        assertThat(out).contains("Sample (first 5 rows)");
    }

    @Test
    void describeTableResolvesUppercaseTableName() {
        assertThat(CONNECTOR.describeTable(source, "DEMO_ORDERS")).contains("600 rows");
    }

    @Test
    void describeTableRejectsUnknownTable() {
        assertThat(CONNECTOR.describeTable(source, "no_such_table"))
                .startsWith("error:")
                .contains("no_such_table");
    }

    @Test
    void describeTableRejectsUnsafeNames() {
        assertThat(CONNECTOR.describeTable(source, "demo_orders; DROP TABLE demo_orders"))
                .startsWith("error: table must be a plain identifier");
        assertThat(CONNECTOR.describeTable(source, "demo_orders UNION SELECT 1"))
                .startsWith("error: table must be a plain identifier");
    }

    @Test
    void runSqlPreviewRendersMarkdownTable() {
        String out =
                CONNECTOR.runSqlPreview(
                        source,
                        "SELECT region, COUNT(*) AS orders"
                                + " FROM demo_orders GROUP BY region ORDER BY region",
                        null,
                        0);

        assertThat(out).doesNotStartWith("error");
        // The report always starts with the SQL block, then the result table.
        assertThat(out).contains("**SQL");
        assertThat(out).containsIgnoringCase("| region | orders |");
        assertThat(out).contains("East").contains("North").contains("South").contains("West");
        assertThat(out).doesNotContain("showing first");
    }

    @Test
    void runSqlPreviewWithQuestionRendersStructuredReport() {
        String out =
                CONNECTOR.runSqlPreview(
                        source,
                        "SELECT COUNT(*) AS orders FROM demo_orders",
                        "How many orders are there?",
                        0);

        assertThat(out).doesNotStartWith("error");
        assertThat(out).startsWith("**查询问题：** How many orders are there?");
        assertThat(out).contains("**SQL");
        assertThat(out).contains("**查询结果：**");
        assertThat(out).contains("| 600 |");
    }

    @Test
    void runSqlPreviewTruncatesAtRowLimit() {
        String out =
                CONNECTOR.runSqlPreview(
                        source, "SELECT order_id FROM demo_orders ORDER BY order_id", null, 5);

        assertThat(out).contains("(showing first 5 rows");
        assertThat(out).contains("| 5 |");
        assertThat(out).doesNotContain("| 6 |");
    }

    @Test
    void runSqlPreviewClampsRowLimitToHardCap() {
        String out = CONNECTOR.runSqlPreview(source, "SELECT order_id FROM demo_orders", null, 500);

        assertThat(out).contains("(showing first 100 rows");
    }

    @Test
    void runSqlPreviewMarksEmptyResults() {
        String out =
                CONNECTOR.runSqlPreview(source, "SELECT * FROM demo_orders WHERE 1 = 0", null, 5);

        assertThat(out).contains("(0 rows returned)");
    }

    @Test
    void runSqlPreviewReportsSeedDeterminism() {
        // Locks the deterministic seed arithmetic: exactly 24 of the 600 orders are refunded.
        String out =
                CONNECTOR.runSqlPreview(
                        source,
                        "SELECT status, COUNT(*) AS n FROM demo_orders"
                                + " GROUP BY status ORDER BY status",
                        null,
                        0);

        assertThat(out).contains("| completed | 576 |");
        assertThat(out).contains("| refunded | 24 |");
    }

    @Test
    void runSqlPreviewReportsSqlErrors() {
        assertThat(
                        CONNECTOR.runSqlPreview(
                                source, "SELECT no_such_column FROM demo_orders", null, 5))
                .startsWith("error:");
    }
}
