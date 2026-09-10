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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.DatasetScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies the four agent-facing tools of {@link DataAgentToolkit} against a real H2 connection
 * (no Spring context): the happy path for each tool plus the guard rails — unknown source ids,
 * the SELECT/WITH gate, unsupported source kinds, per-user dataset scoping, and cross-tenant
 * table-reference rejection. The injected {@link RuntimeContext} is {@code null} here (unit level);
 * owner resolution then relies on the typed {@link DatasetScope}.
 */
class DataAgentToolkitTest {

    private static final DatasetScope SCOPE = new DatasetScope("tester");
    private static final RuntimeContext RC = null;

    private static DataAgentToolkit toolkit;

    @BeforeAll
    static void setUp() throws Exception {
        TenantTablesFixture.seedTenantTables();
        toolkit =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(List.of(TenantTablesFixture.demoSource())),
                        new JdbcSqlConnector());
    }

    @Test
    void listsConfiguredSources() {
        assertThat(toolkit.listDataSources(SCOPE, RC))
                .contains("demo-db | jdbc | Demo analytics DB")
                .contains("tenant_storage_utilization")
                .contains("(demo,h2)");
    }

    @Test
    void listsNoSourcesGracefully() {
        DataAgentToolkit empty =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(List.of()), new JdbcSqlConnector());

        assertThat(empty.listDataSources(SCOPE, RC)).startsWith("none:");
    }

    @Test
    void describesTableForKnownSource() {
        assertThat(toolkit.describeTable(SCOPE, RC, "demo-db", "tenant_storage_utilization"))
                .contains("30 rows");
    }

    @Test
    void describeTableRejectsUnknownSource() {
        assertThat(toolkit.describeTable(SCOPE, RC, "nope", "tenant_storage_utilization"))
                .isEqualTo("error: unknown or not permitted source_id 'nope'");
    }

    @Test
    void describeTableRejectsUnsupportedSourceKind() {
        DataSource httpSource =
                new DataSource("api", "HTTP API", null, "http", null, List.of(), Map.of());
        DataAgentToolkit httpOnly =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(List.of(httpSource)),
                        new JdbcSqlConnector());

        assertThat(httpOnly.describeTable(SCOPE, RC, "api", "orders"))
                .isEqualTo("error: no SQL connector available for source kind 'http'");
    }

    @Test
    void runsSqlPreview() {
        String out =
                toolkit.runSqlPreview(
                        SCOPE,
                        RC,
                        "demo-db",
                        "SELECT COUNT(*) AS n FROM tenant_storage_utilization",
                        null,
                        null);

        assertThat(out).doesNotStartWith("error");
        assertThat(out).contains("| 30 |");
    }

    @Test
    void runSqlPreviewRejectsNonSelect() {
        assertThat(
                        toolkit.runSqlPreview(
                                SCOPE,
                                RC,
                                "demo-db",
                                "DELETE FROM tenant_storage_utilization",
                                null,
                                null))
                .isEqualTo("error: only SELECT / WITH statements are allowed");
        assertThat(
                        toolkit.runSqlPreview(
                                SCOPE,
                                RC,
                                "demo-db",
                                "INSERT INTO project_info VALUES (1,'x','y')",
                                null,
                                null))
                .isEqualTo("error: only SELECT / WITH statements are allowed");
    }

    @Test
    void runSqlPreviewRejectsUnknownSource() {
        assertThat(toolkit.runSqlPreview(SCOPE, RC, "nope", "SELECT 1", null, null))
                .isEqualTo("error: unknown or not permitted source_id 'nope'");
    }

    @Test
    void scopesUserDatasetsToOwner() {
        DataSource mine =
                new DataSource(
                        "ds1",
                        "Mine",
                        null,
                        "jdbc",
                        null,
                        List.of("user-dataset"),
                        Map.of("jdbcUrl", "jdbc:h2:mem:unused", "ownerId", "bob"));
        DataAgentToolkit scoped =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(
                                List.of(mine, TenantTablesFixture.demoSource())),
                        new JdbcSqlConnector());

        assertThat(scoped.listDataSources(new DatasetScope("bob"), RC)).contains("ds1");
        assertThat(scoped.listDataSources(new DatasetScope("alice"), RC)).doesNotContain("ds1");
        // No scope and no RuntimeContext userId (non-chat channel) exposes globals only.
        assertThat(scoped.listDataSources(null, RC)).doesNotContain("ds1").contains("demo-db");
    }

    @Test
    void fallsBackToRuntimeContextUserIdWhenTypedScopeAbsent() {
        DataSource mine =
                new DataSource(
                        "ds1",
                        "Mine",
                        null,
                        "jdbc",
                        null,
                        List.of("user-dataset"),
                        Map.of("jdbcUrl", "jdbc:h2:mem:unused", "ownerId", "bob"));
        DataAgentToolkit scoped =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(
                                List.of(mine, TenantTablesFixture.demoSource())),
                        new JdbcSqlConnector());
        RuntimeContext baked = RuntimeContext.builder().userId("bob").sessionId("s").build();

        // Harness out-of-band tool execution supplies a baked context (userId only, no typed
        // attributes); the toolkit must still resolve the owner from it.
        assertThat(scoped.listDataSources(null, baked)).contains("ds1");
    }

    @Test
    void blocksCrossTenantTableReference() {
        DataSource mine =
                new DataSource(
                        "ds1",
                        "Mine",
                        null,
                        "jdbc",
                        null,
                        List.of("user-dataset"),
                        Map.of(
                                "jdbcUrl", "jdbc:mysql://127.0.0.1:3306/data_agent",
                                "username", "u",
                                "password", "p",
                                "ownerId", "bob",
                                "tableName", "ds_bob0000_11111111_orders"));
        DataAgentToolkit tk =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(List.of(mine)), new JdbcSqlConnector());
        DatasetScope bob = new DatasetScope("bob");

        assertThat(
                        tk.runSqlPreview(
                                bob,
                                RC,
                                "ds1",
                                "SELECT * FROM ds_alice00_22222222_orders",
                                null,
                                null))
                .isEqualTo(
                        "error: query references a dataset table you do not own:"
                                + " ds_alice00_22222222_orders");
        // Own table passes the guard; any failure afterwards is a connection error, not the guard.
        assertThat(
                        tk.runSqlPreview(
                                bob,
                                RC,
                                "ds1",
                                "SELECT * FROM ds_bob0000_11111111_orders",
                                null,
                                null))
                .doesNotStartWith("error: query references a dataset table you do not own");
        // Non-ds_ tables (shared analytics content) are not affected by the guard.
        assertThat(tk.runSqlPreview(bob, RC, "ds1", "SELECT * FROM project_info", null, null))
                .doesNotStartWith("error: query references a dataset table you do not own");
    }

    @Test
    void renderChartBuildsDeterministicOption() {
        String out =
                toolkit.renderChart(
                        "分析下云南省 30 天走势",
                        List.of("dt", "cnt"),
                        List.of(
                                List.of("2026-09-01", "10"),
                                List.of("2026-09-02", "12"),
                                List.of("2026-09-03", "11")));

        assertThat(out).contains("\"chart\":\"echarts\"");
        assertThat(out).contains("\"chartType\":\"line\"");
        assertThat(out).contains("\"series\"");
    }

    @Test
    void renderChartRejectsUnchartableData() {
        assertThat(toolkit.renderChart("q", List.of("a"), List.of(List.of("x"))))
                .startsWith("error:");
    }
}
