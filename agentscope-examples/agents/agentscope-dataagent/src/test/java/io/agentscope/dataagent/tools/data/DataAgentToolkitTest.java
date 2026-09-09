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
 * Verifies the four agent-facing tools of {@link DataAgentToolkit} against a real H2 connection
 * (no Spring context): the happy path for each tool plus the guard rails — unknown source ids,
 * the SELECT/WITH gate, and unsupported source kinds.
 */
class DataAgentToolkitTest {

    private static DataAgentToolkit toolkit;

    @BeforeAll
    static void setUp() throws Exception {
        DemoOrdersFixture.seedDemoOrders();
        toolkit =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(List.of(DemoOrdersFixture.demoSource())),
                        new JdbcSqlConnector(),
                        new StubChartRenderer());
    }

    @Test
    void listsConfiguredSources() {
        assertThat(toolkit.listDataSources())
                .contains("demo-db | jdbc | Demo analytics DB")
                .contains("demo_orders")
                .contains("(demo,h2)");
    }

    @Test
    void listsNoSourcesGracefully() {
        DataAgentToolkit empty =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(List.of()),
                        new JdbcSqlConnector(),
                        new StubChartRenderer());

        assertThat(empty.listDataSources()).startsWith("none:");
    }

    @Test
    void describesTableForKnownSource() {
        assertThat(toolkit.describeTable("demo-db", "demo_orders")).contains("600 rows");
    }

    @Test
    void describeTableRejectsUnknownSource() {
        assertThat(toolkit.describeTable("nope", "demo_orders"))
                .isEqualTo("error: unknown source_id 'nope'");
    }

    @Test
    void describeTableRejectsUnsupportedSourceKind() {
        DataSource httpSource =
                new DataSource("api", "HTTP API", null, "http", null, List.of(), Map.of());
        DataAgentToolkit httpOnly =
                new DataAgentToolkit(
                        new InMemoryDataSourceRegistry(List.of(httpSource)),
                        new JdbcSqlConnector(),
                        new StubChartRenderer());

        assertThat(httpOnly.describeTable("api", "orders"))
                .isEqualTo("error: no SQL connector available for source kind 'http'");
    }

    @Test
    void runsSqlPreview() {
        String out =
                toolkit.runSqlPreview(
                        "demo-db", "SELECT COUNT(*) AS orders FROM demo_orders", null, null);

        assertThat(out).doesNotStartWith("error");
        assertThat(out).contains("| 600 |");
    }

    @Test
    void runSqlPreviewRejectsNonSelect() {
        assertThat(toolkit.runSqlPreview("demo-db", "DELETE FROM demo_orders", null, null))
                .isEqualTo("error: only SELECT / WITH statements are allowed");
        assertThat(
                        toolkit.runSqlPreview(
                                "demo-db", "INSERT INTO demo_orders VALUES (1)", null, null))
                .isEqualTo("error: only SELECT / WITH statements are allowed");
    }

    @Test
    void runSqlPreviewRejectsUnknownSource() {
        assertThat(toolkit.runSqlPreview("nope", "SELECT 1", null, null))
                .isEqualTo("error: unknown source_id 'nope'");
    }

    @Test
    void renderChartEchoesStubStatus() {
        String out = toolkit.renderChart("bar", "{\"mark\":\"bar\",\"data\":{\"values\":[]}}");

        assertThat(out).contains("ok: chart spec accepted");
        assertThat(out).contains("type=bar");
    }

    @Test
    void renderChartRejectsEmptySpec() {
        assertThat(toolkit.renderChart("bar", " ")).isEqualTo("error: vega-lite spec is empty");
    }
}
