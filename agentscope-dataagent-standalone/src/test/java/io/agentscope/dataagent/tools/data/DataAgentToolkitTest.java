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
 * Verifies the four TC-style agent-facing tools of {@link DataAgentToolkit} against a real H2
 * connection (no Spring context): the happy path for each tool plus the guard rails — unknown
 * source ids, the SELECT/WITH gate, unsupported source kinds, per-user dataset scoping, and
 * cross-tenant table-reference rejection. The injected {@link RuntimeContext} is {@code null}
 * here (unit level); owner resolution then relies on the typed {@link DatasetScope}.
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
    void prepareDataContextShowsTableStructure() {
        String out = toolkit.prepareDataContext(SCOPE, RC, "demo-db", "tenant_storage_utilization");
        // Output comes from stored metadata, not DB query
        assertThat(out).contains("Demo analytics DB");
        assertThat(out).contains("storage-optimization analysis");
    }

    @Test
    void prepareDataContextRejectsUnknownSource() {
        assertThat(toolkit.prepareDataContext(SCOPE, RC, "nope", "tenant_storage_utilization"))
                .isEqualTo("error: 未知或不 permitted 的 source_id 'nope'");
    }

    @Test
    void prepareDataContextRejectsUnsupportedSourceKind() {
        DataSource httpSource =
                new DataSource("api", "HTTP API", null, "http", null, List.of(), Map.of());
        DataAgentToolkit httpOnly =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(List.of(httpSource)),
                        new JdbcSqlConnector());

        assertThat(httpOnly.prepareDataContext(SCOPE, RC, "api", "orders"))
                .isEqualTo("error: 数据源类型 'http' 不支持 SQL 查询");
    }

    @Test
    void runsQueryStructuredData() {
        String out =
                toolkit.queryStructuredData(
                        SCOPE,
                        RC,
                        "demo-db",
                        "SELECT COUNT(*) AS n FROM tenant_storage_utilization",
                        null,
                        null);

        assertThat(out).doesNotStartWith("error");
        assertThat(out).contains("| 30 |");
        assertThat(out).contains("## 查询结果");
    }

    @Test
    void queryStructuredDataRejectsNonSelect() {
        assertThat(
                        toolkit.queryStructuredData(
                                SCOPE,
                                RC,
                                "demo-db",
                                "DELETE FROM tenant_storage_utilization",
                                null,
                                null))
                .isEqualTo("error: 只允许 SELECT / WITH 语句");
        assertThat(
                        toolkit.queryStructuredData(
                                SCOPE,
                                RC,
                                "demo-db",
                                "INSERT INTO project_info VALUES (1,'x','y')",
                                null,
                                null))
                .isEqualTo("error: 只允许 SELECT / WITH 语句");
    }

    @Test
    void queryStructuredDataRejectsUnknownSource() {
        assertThat(toolkit.queryStructuredData(SCOPE, RC, "nope", "SELECT 1", null, null))
                .startsWith("error: 未知或不 permitted 的 source_id 'nope'");
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

        // Bob can see his own dataset via prepare_data_context
        assertThat(scoped.prepareDataContext(new DatasetScope("bob"), RC, "ds1", "t"))
                .doesNotStartWith("error: 未知");
        // Alice cannot see Bob's dataset
        assertThat(scoped.prepareDataContext(new DatasetScope("alice"), RC, "ds1", "t"))
                .startsWith("error: 未知");
        // No scope exposes globals only
        assertThat(scoped.prepareDataContext(null, RC, "ds1", "t")).startsWith("error: 未知");
        assertThat(scoped.prepareDataContext(null, RC, "demo-db", "tenant_storage_utilization"))
                .doesNotStartWith("error: 未知");
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
        assertThat(scoped.prepareDataContext(null, baked, "ds1", "t"))
                .doesNotStartWith("error: 未知");
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
                        tk.queryStructuredData(
                                bob,
                                RC,
                                "ds1",
                                "SELECT * FROM ds_alice00_22222222_orders",
                                null,
                                null))
                .isEqualTo("error: 查询引用了不属于您的数据表: ds_alice00_22222222_orders");
        // Own table passes the guard; any failure afterwards is a connection error, not the guard.
        assertThat(
                        tk.queryStructuredData(
                                bob,
                                RC,
                                "ds1",
                                "SELECT * FROM ds_bob0000_11111111_orders",
                                null,
                                null))
                .doesNotStartWith("error: 查询引用了不属于您的数据表");
        // Non-ds_ tables (shared analytics content) are not affected by the guard.
        assertThat(tk.queryStructuredData(bob, RC, "ds1", "SELECT * FROM project_info", null, null))
                .doesNotStartWith("error: 查询引用了不属于您的数据表");
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
                                List.of("2026-09-03", "11")),
                        null,
                        null);

        assertThat(out).contains("\"chart\":\"echarts\"");
        assertThat(out).contains("\"chartType\":\"line\"");
        assertThat(out).contains("\"series\"");
    }

    @Test
    void renderChartAppliesTargetMarkLine() {
        String out =
                toolkit.renderChart(
                        "活跃用户趋势",
                        List.of("dt", "cnt"),
                        List.of(List.of("2026-09-01", "10"), List.of("2026-09-02", "12")),
                        "20",
                        "日均目标");
        assertThat(out).contains("markLine");
        assertThat(out).contains("日均目标");
    }

    @Test
    void renderChartRejectsUnchartableData() {
        assertThat(toolkit.renderChart("q", List.of("a"), List.of(List.of("x")), null, null))
                .startsWith("error:");
    }

    @Test
    void prepareDataContextBatchReturnsAllTables() {
        DataAgentToolkit scoped =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(
                                List.of(
                                        TenantTablesFixture.demoSource(),
                                        userDataset("ds_a", "tester", "col_alpha"),
                                        userDataset("ds_b", "tester", "col_beta"))),
                        new JdbcSqlConnector());

        String out =
                scoped.prepareDataContext(
                        SCOPE,
                        RC,
                        null,
                        null,
                        List.of(
                                Map.of(
                                        "source_id", "demo-db",
                                        "table", "tenant_storage_utilization"),
                                Map.of("source_id", "ds_a", "table", "t_a"),
                                Map.of("source_id", "ds_b", "table", "t_b")));

        // One call returns a section per table
        assertThat(out).contains("## Demo analytics DB");
        assertThat(out).contains("## DS ds_a");
        assertThat(out).contains("## DS ds_b");
        assertThat(out).contains("col_alpha");
        assertThat(out).contains("col_beta");
    }

    @Test
    void prepareDataContextBatchInlineErrorForInvisibleSource() {
        DataAgentToolkit scoped =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(
                                List.of(
                                        userDataset("ds_a", "tester", "col_alpha"),
                                        userDataset("ds_secret", "bob", "col_secret"))),
                        new JdbcSqlConnector());

        // Tester mixes one of Bob's datasets into a batch prepare
        String out =
                scoped.prepareDataContext(
                        SCOPE,
                        RC,
                        null,
                        null,
                        List.of(
                                Map.of("source_id", "ds_a", "table", "t_a"),
                                Map.of("source_id", "ds_secret", "table", "t_secret")));

        // Inline error at that entry's position; the visible entry still returns normally
        assertThat(out).contains("## DS ds_a");
        assertThat(out).contains("col_alpha");
        assertThat(out).contains("error: 未知或不 permitted 的 source_id 'ds_secret'");
        // No column/description leak from the invisible dataset
        assertThat(out).doesNotContain("col_secret");
        assertThat(out).doesNotContain("Dataset ds_secret");
    }

    @Test
    void prepareDataContextBatchRejectsMoreThanFive() {
        String out =
                toolkit.prepareDataContext(
                        SCOPE,
                        RC,
                        null,
                        null,
                        List.of(
                                Map.of("source_id", "demo-db", "table", "t1"),
                                Map.of("source_id", "demo-db", "table", "t2"),
                                Map.of("source_id", "demo-db", "table", "t3"),
                                Map.of("source_id", "demo-db", "table", "t4"),
                                Map.of("source_id", "demo-db", "table", "t5"),
                                Map.of("source_id", "demo-db", "table", "t6")));

        // Rejected outright — no partial execution
        assertThat(out).startsWith("error:");
        assertThat(out).contains("5");
        assertThat(out).doesNotContain("## ");
    }

    @Test
    void prepareDataContextSingleTableCompatible() {
        // Old single-table form (source_id + table, no tables) keeps the pre-change output shape
        String out =
                toolkit.prepareDataContext(
                        SCOPE, RC, "demo-db", "tenant_storage_utilization", null);
        assertThat(out).startsWith("# Demo analytics DB");
        assertThat(out).contains("storage-optimization analysis");
        assertThat(out).doesNotContain("## ");
    }

    @Test
    void prepareDataContextGuidesWhenNoParams() {
        // source_id/table are optional in the tool schema (batch mode omits them); with neither
        // form supplied the tool must return guidance instead of a "'null'" lookup error
        assertThat(toolkit.prepareDataContext(SCOPE, RC, null, null)).startsWith("error: 缺少参数");
    }

    /** Minimal JDBC-kind user dataset whose schema output comes from stored metadata only. */
    private static DataSource userDataset(String id, String owner, String columnName) {
        return new DataSource(
                id,
                "DS " + id,
                "Dataset " + id,
                "jdbc",
                null,
                List.of("user-dataset"),
                Map.of(
                        "jdbcUrl",
                        "jdbc:h2:mem:unused",
                        "ownerId",
                        owner,
                        "columnSchemaJson",
                        "[{\"name\":\""
                                + columnName
                                + "\",\"originalName\":\""
                                + columnName
                                + "\",\"sqlType\":\"VARCHAR\",\"description\":\"d\"}]"));
    }
}
