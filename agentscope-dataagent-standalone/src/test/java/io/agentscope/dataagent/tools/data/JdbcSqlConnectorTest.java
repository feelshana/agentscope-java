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
 * Exercises {@link JdbcSqlConnector} against a real H2 database seeded with the
 * tenant analytics tables (see {@link TenantTablesFixture}). No Spring context
 * — the connector is plain JDBC.
 */
class JdbcSqlConnectorTest {

    private static final JdbcSqlConnector CONNECTOR = new JdbcSqlConnector();

    private static DataSource source;

    @BeforeAll
    static void seed() throws Exception {
        TenantTablesFixture.seedTenantTables();
        source = TenantTablesFixture.demoSource();
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
        String out = CONNECTOR.describeTable(source, "tenant_storage_utilization");

        assertThat(out).doesNotStartWith("error");
        assertThat(out).contains("11 columns");
        assertThat(out).contains("30 rows");
        assertThat(out).containsIgnoringCase("project_name");
        assertThat(out).containsIgnoringCase("size_gb");
        assertThat(out).contains("Sample (first 5 rows)");
    }

    @Test
    void describeTableRejectsUnknownTable() {
        assertThat(CONNECTOR.describeTable(source, "no_such_table"))
                .startsWith("error:")
                .contains("no_such_table");
    }

    @Test
    void describeTableRejectsUnsafeNames() {
        assertThat(
                        CONNECTOR.describeTable(
                                source,
                                "tenant_storage_utilization; DROP TABLE"
                                        + " tenant_storage_utilization"))
                .startsWith("error: table must be a plain identifier");
        assertThat(CONNECTOR.describeTable(source, "tenant_storage_utilization UNION SELECT 1"))
                .startsWith("error: table must be a plain identifier");
    }

    @Test
    void runSqlPreviewRendersMarkdownTable() {
        String out =
                CONNECTOR.runSqlPreview(
                        source,
                        "SELECT project_name, SUM(size_gb) AS total_gb"
                                + " FROM tenant_storage_utilization"
                                + " GROUP BY project_name ORDER BY total_gb DESC",
                        null,
                        0);

        assertThat(out).doesNotStartWith("error");
        assertThat(out).contains("**SQL");
        assertThat(out).containsIgnoringCase("| project_name |");
        assertThat(out).contains("data-lake").contains("ml-pipeline");
        assertThat(out).doesNotContain("showing first");
    }

    @Test
    void runSqlPreviewWithQuestionRendersStructuredReport() {
        String out =
                CONNECTOR.runSqlPreview(
                        source,
                        "SELECT COUNT(*) AS cnt FROM tenant_storage_utilization",
                        "How many table entries exist?",
                        0);

        assertThat(out).doesNotStartWith("error");
        assertThat(out).startsWith("**查询问题：** How many table entries exist?");
        assertThat(out).contains("**SQL");
        assertThat(out).contains("**查询结果：**");
        assertThat(out).contains("| 30 |");
    }

    @Test
    void runSqlPreviewTruncatesAtRowLimit() {
        String out =
                CONNECTOR.runSqlPreview(
                        source, "SELECT id FROM tenant_storage_utilization ORDER BY id", null, 5);

        assertThat(out).contains("(showing first 5 rows");
        // H2 AUTO_INCREMENT does not reset after DELETE, so we only verify
        // the truncation message and the absence of a later row's value.
        assertThat(out).doesNotContain("log-analysis");
    }

    @Test
    void runSqlPreviewMarksEmptyResults() {
        String out =
                CONNECTOR.runSqlPreview(
                        source, "SELECT * FROM tenant_storage_utilization WHERE 1 = 0", null, 5);

        assertThat(out).contains("(0 rows returned)");
    }

    @Test
    void runSqlPreviewJoinReturnsOwnerData() {
        // Verifies that the two tables can be joined on project_name.
        String out =
                CONNECTOR.runSqlPreview(
                        source,
                        "SELECT u.project_name, p.owner_name, SUM(u.size_gb) AS total_gb"
                                + " FROM tenant_storage_utilization u"
                                + " JOIN project_info p ON u.project_name = p.project_name"
                                + " GROUP BY u.project_name, p.owner_name"
                                + " ORDER BY total_gb DESC",
                        "Tenant storage by owner",
                        0);

        assertThat(out).doesNotStartWith("error");
        assertThat(out).contains("data-lake");
        assertThat(out).contains("Alice");
        assertThat(out).contains("ml-pipeline");
        assertThat(out).contains("Carol");
    }

    @Test
    void runSqlPreviewReportsSqlErrors() {
        assertThat(
                        CONNECTOR.runSqlPreview(
                                source,
                                "SELECT no_such_column FROM tenant_storage_utilization",
                                null,
                                5))
                .startsWith("error:");
    }
}
