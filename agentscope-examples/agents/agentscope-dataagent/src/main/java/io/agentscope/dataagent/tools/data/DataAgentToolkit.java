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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent-facing toolkit for data-analysis primitives: list configured sources, describe a table,
 * preview a SQL query, render a chart. Stateless singleton; backing connectors are resolved
 * through a {@link DataSourceRegistry}, {@link SqlConnector} and {@link ChartRenderer} so
 * operators can swap real implementations without touching this class.
 *
 * <p>The bundled {@link JdbcSqlConnector} serves sources with {@code kind: jdbc} (the default
 * deployment registers a {@code test-data} MySQL source with the tenant analytics tables); other
 * source kinds fall through to a clear error string so the agent surfaces the limitation rather
 * than hallucinating query results.
 *
 * <p>Registered onto the main {@code data-agent} at startup; user-custom agents may opt in by
 * listing the tools in their workspace {@code tools.json}.
 */
public final class DataAgentToolkit {

    private final DataSourceRegistry registry;
    private final SqlConnector sqlConnector;
    private final ChartRenderer chartRenderer;

    public DataAgentToolkit(
            DataSourceRegistry registry, SqlConnector sqlConnector, ChartRenderer chartRenderer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sqlConnector = Objects.requireNonNull(sqlConnector, "sqlConnector");
        this.chartRenderer = Objects.requireNonNull(chartRenderer, "chartRenderer");
    }

    @Tool(
            name = "list_data_sources",
            description =
                    """
                    List the data sources the admin has configured for this deployment. Each entry \
                    is reported as 'id | kind | label — description (tags)'. Call this before \
                    drafting any SQL so you pick a source the runtime actually knows about. \
                    Returns 'none' when no sources are configured.\
                    """)
    public String listDataSources() {
        List<DataSource> all = registry.list();
        if (all.isEmpty()) {
            return "none: no data sources are configured. Ask the operator to seed"
                    + " dataagent.data.sources in agentscope.json.";
        }
        StringBuilder sb = new StringBuilder();
        for (DataSource ds : all) {
            sb.append(ds.id()).append(" | ").append(ds.kind()).append(" | ").append(ds.label());
            if (ds.description() != null && !ds.description().isBlank()) {
                sb.append(" — ").append(ds.description());
            }
            if (!ds.tags().isEmpty()) {
                sb.append(" (").append(String.join(",", ds.tags())).append(")");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(
            name = "describe_table",
            description =
                    """
                    Return the column schema, total row count, and a short sample for a table in \
                    a configured data source. Use after list_data_sources to confirm the columns \
                    you intend to project / filter / group by before writing the query.\
                    """)
    public String describeTable(
            @ToolParam(name = "source_id", description = "Data source id from list_data_sources")
                    String sourceId,
            @ToolParam(
                            name = "table",
                            description = "Fully-qualified table name as understood by the source")
                    String table) {
        Optional<DataSource> ds = registry.findById(sourceId);
        if (ds.isEmpty()) {
            return "error: unknown source_id '" + sourceId + "'";
        }
        if (table == null || table.isBlank()) {
            return "error: table must not be blank";
        }
        if (!sqlConnector.supports(ds.get())) {
            return "error: no SQL connector available for source kind '" + ds.get().kind() + "'";
        }
        return sqlConnector.describeTable(ds.get(), table);
    }

    @Tool(
            name = "run_sql_preview",
            description =
                    """
                    Execute a read-only SQL query against a configured data source and return the \
                    first N rows as a markdown report (default 20, hard cap 100). The report \
                    includes the natural-language question being answered, the SQL statement, and \
                    the result table — making every invocation self-documenting for the user. Only \
                    SELECT / WITH statements are accepted. Run describe_table first to confirm \
                    column names, then validate your query here before reporting numbers.\
                    """)
    public String runSqlPreview(
            @ToolParam(name = "source_id", description = "Data source id from list_data_sources")
                    String sourceId,
            @ToolParam(name = "sql", description = "SELECT-only SQL statement") String sql,
            @ToolParam(
                            name = "question",
                            description =
                                    "Natural-language question being answered by this query"
                                            + " (in Chinese); displayed above the SQL and result"
                                            + " table so the tool block is self-documenting",
                            required = false)
                    String question,
            @ToolParam(
                            name = "row_limit",
                            description = "Max rows to return; the connector enforces a hard cap",
                            required = false)
                    Integer rowLimit) {
        Optional<DataSource> ds = registry.findById(sourceId);
        if (ds.isEmpty()) {
            return "error: unknown source_id '" + sourceId + "'";
        }
        if (sql == null || sql.isBlank()) {
            return "error: sql must not be blank";
        }
        String trimmed = sql.trim().toLowerCase();
        if (!trimmed.startsWith("select") && !trimmed.startsWith("with")) {
            return "error: only SELECT / WITH statements are allowed";
        }
        if (!sqlConnector.supports(ds.get())) {
            return "error: no SQL connector available for source kind '" + ds.get().kind() + "'";
        }
        return sqlConnector.runSqlPreview(ds.get(), sql, question, rowLimit != null ? rowLimit : 0);
    }

    @Tool(
            name = "render_chart",
            description =
                    """
                    Render a chart from a Vega-Lite spec. chart_type is one of \
                    line | bar | area | scatter. The spec must include the data inline. Returns a \
                    short status string; the SPA renders the chart client-side from the same spec \
                    so no server-side image is produced in v1.\
                    """)
    public String renderChart(
            @ToolParam(name = "chart_type", description = "line | bar | area | scatter")
                    String chartType,
            @ToolParam(
                            name = "vega_lite_spec",
                            description = "Vega-Lite JSON spec including inline data")
                    String vegaLiteSpec) {
        return chartRenderer.render(chartType, vegaLiteSpec);
    }
}
