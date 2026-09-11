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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.dataset.DatasetContextProvider;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.KnowledgeGraphService;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionEntity;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent-facing toolkit for data-analysis primitives: list configured sources, describe a table,
 * preview a SQL query, render a chart. Stateless singleton; backing connectors are resolved
 * through a {@link DataSourceRegistry}, {@link SqlConnector} and {@link ChartRenderer} so
 * operators can swap real implementations without touching this class.
 *
 * <p>The bundled {@link JdbcSqlConnector} serves sources with {@code kind: jdbc}: the optional
 * seeded analytics source (disabled by default) plus one source per user-uploaded dataset, all
 * living as prefixed tables in the configured dataset database; other source kinds fall through
 * to a clear error string so the agent surfaces the limitation rather than hallucinating results.
 *
 * <p>Registered onto the main {@code data-agent} at startup; user-custom agents may opt in by
 * listing the tools in their workspace {@code tools.json}.
 */
public final class DataAgentToolkit {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DataAgentToolkit.class);

    private static final Pattern DATASET_TABLE = Pattern.compile("ds_[a-z0-9_]+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSourceRegistry registry;
    private final SqlConnector sqlConnector;
    private final DatasetContextProvider contextProvider;
    private final ChartOptionRepository chartOptions;
    private final KnowledgeGraphService knowledgeGraph;
    private final ConversationScopeRegistry conversationScopes;

    public DataAgentToolkit(DataSourceRegistry registry, SqlConnector sqlConnector) {
        this(registry, sqlConnector, null, null, null, null);
    }

    public DataAgentToolkit(
            DataSourceRegistry registry,
            SqlConnector sqlConnector,
            DatasetContextProvider contextProvider) {
        this(registry, sqlConnector, contextProvider, null, null, null);
    }

    public DataAgentToolkit(
            DataSourceRegistry registry,
            SqlConnector sqlConnector,
            DatasetContextProvider contextProvider,
            ChartOptionRepository chartOptions) {
        this(registry, sqlConnector, contextProvider, chartOptions, null, null);
    }

    public DataAgentToolkit(
            DataSourceRegistry registry,
            SqlConnector sqlConnector,
            DatasetContextProvider contextProvider,
            ChartOptionRepository chartOptions,
            KnowledgeGraphService knowledgeGraph) {
        this(registry, sqlConnector, contextProvider, chartOptions, knowledgeGraph, null);
    }

    public DataAgentToolkit(
            DataSourceRegistry registry,
            SqlConnector sqlConnector,
            DatasetContextProvider contextProvider,
            ChartOptionRepository chartOptions,
            KnowledgeGraphService knowledgeGraph,
            ConversationScopeRegistry conversationScopes) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sqlConnector = Objects.requireNonNull(sqlConnector, "sqlConnector");
        this.contextProvider = contextProvider;
        this.chartOptions = chartOptions;
        this.knowledgeGraph = knowledgeGraph;
        this.conversationScopes = conversationScopes;
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
    public String listDataSources(DatasetScope scope, RuntimeContext rc) {
        DatasetScope eff = effectiveScope(scope, rc);
        List<DataSource> all = visible(eff);
        if (eff == null) {
            log.warn(
                    "list_data_sources: no owner resolvable (typed scope absent and RuntimeContext"
                            + " userId null); only global sources visible (registry has {}"
                            + " source(s))",
                    registry.list().size());
        } else {
            log.info(
                    "list_data_sources: owner={} (typedScope={}) registryIds={} visibleIds={}",
                    eff.ownerId(),
                    scope != null,
                    registry.list().stream().map(DataSource::id).toList(),
                    all.stream().map(DataSource::id).toList());
        }
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
        String out = sb.toString().stripTrailing();
        if (scope != null && contextProvider != null) {
            String rel = contextProvider.relationshipsText(scope.ownerId(), scope.groupIds());
            if (rel != null && !rel.isBlank()) {
                out = out + "\n\n## 数据集关系说明（用户提供）\n" + rel;
            }
            String terms = contextProvider.semanticTermsText();
            if (terms != null && !terms.isBlank()) {
                out = out + "\n\n## 业务术语（语义配置）\n" + terms;
            }
        }
        return out;
    }

    /**
     * Resolves the tenant scope for a tool call. The typed {@link DatasetScope} is preferred; when
     * the harness executes tools out-of-band it supplies a baked {@link RuntimeContext} carrying
     * only userId/sessionId (no typed attributes), so fall back to {@code rc.getUserId()}.
     */
    private DatasetScope effectiveScope(DatasetScope scope, RuntimeContext rc) {
        DatasetScope base = null;
        if (scope != null && scope.ownerId() != null) {
            base = scope;
        } else if (rc != null && rc.getUserId() != null && !rc.getUserId().isBlank()) {
            base = new DatasetScope(rc.getUserId());
        }
        if (base == null) {
            return null;
        }
        if (!base.hasGroupFilter()
                && conversationScopes != null
                && rc != null
                && rc.getSessionId() != null) {
            java.util.List<String> groups = conversationScopes.get(rc.getSessionId());
            if (groups != null && !groups.isEmpty()) {
                return new DatasetScope(base.ownerId(), groups);
            }
        }
        return base;
    }

    /**
     * Data sources visible to the current turn: deployment-global sources (no {@code ownerId},
     * e.g. the seeded app-db / test-data) plus every dataset owned by the calling user. Users do
     * not pre-select datasets — the agent reads this full list and chooses — so ownership is the
     * only filter. When no {@link DatasetScope} is present (a non-chat channel that never set one)
     * only global sources are exposed, so user datasets can never leak across tenants.
     */
    private List<DataSource> visible(DatasetScope scope) {
        List<DataSource> all = registry.list();
        if (scope == null) {
            return all.stream().filter(this::isGlobal).toList();
        }
        if (scope.hasGroupFilter()) {
            // TC-style narrowed scope: only datasets inside the selected knowledge bases.
            java.util.Set<String> groups = new java.util.HashSet<>(scope.groupIds());
            return all.stream()
                    .filter(
                            ds ->
                                    ds.properties() != null
                                            && groups.contains(ds.properties().get("groupId")))
                    .toList();
        }
        return all.stream().filter(ds -> isGlobal(ds) || isMine(ds, scope)).toList();
    }

    private Optional<DataSource> resolveScoped(DatasetScope scope, String sourceId) {
        return visible(scope).stream().filter(ds -> ds.id().equals(sourceId)).findFirst();
    }

    private boolean isGlobal(DataSource ds) {
        return ds.properties() == null || !ds.properties().containsKey("ownerId");
    }

    private boolean isMine(DataSource ds, DatasetScope scope) {
        String owner = ds.properties() == null ? null : ds.properties().get("ownerId");
        return scope.ownerId() != null && scope.ownerId().equals(owner);
    }

    /**
     * All dataset tables live in one shared database, so ownership cannot rely on separate
     * schemas: reject any {@code ds_*} table reference in the SQL that is not one of the caller's
     * visible datasets. Non-{@code ds_} tables (shared analytics content) are unaffected.
     */
    private String checkCrossTable(DatasetScope scope, String sql) {
        Set<String> allowed = new HashSet<>();
        for (DataSource ds : visible(scope)) {
            String t = ds.properties() == null ? null : ds.properties().get("tableName");
            if (t != null && !t.isBlank()) {
                allowed.add(t.toLowerCase());
            }
        }
        Matcher m = DATASET_TABLE.matcher(sql.toLowerCase());
        while (m.find()) {
            if (!allowed.contains(m.group())) {
                return "error: query references a dataset table you do not own: " + m.group();
            }
        }
        return null;
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
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "source_id", description = "Data source id from list_data_sources")
                    String sourceId,
            @ToolParam(
                            name = "table",
                            description = "Fully-qualified table name as understood by the source")
                    String table) {
        Optional<DataSource> ds = resolveScoped(effectiveScope(scope, rc), sourceId);
        if (ds.isEmpty()) {
            return "error: unknown or not permitted source_id '" + sourceId + "'";
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
            DatasetScope scope,
            RuntimeContext rc,
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
        DatasetScope eff = effectiveScope(scope, rc);
        Optional<DataSource> ds = resolveScoped(eff, sourceId);
        if (ds.isEmpty()) {
            return "error: unknown or not permitted source_id '" + sourceId + "'";
        }
        if (sql == null || sql.isBlank()) {
            return "error: sql must not be blank";
        }
        String trimmed = sql.trim().toLowerCase();
        if (!trimmed.startsWith("select") && !trimmed.startsWith("with")) {
            return "error: only SELECT / WITH statements are allowed";
        }
        String crossTable = checkCrossTable(eff, sql);
        if (crossTable != null) {
            return crossTable;
        }
        if (!sqlConnector.supports(ds.get())) {
            return "error: no SQL connector available for source kind '" + ds.get().kind() + "'";
        }
        return sqlConnector.runSqlPreview(ds.get(), sql, question, rowLimit != null ? rowLimit : 0);
    }

    @Tool(
            name = "find_related_tables",
            description =
                    """
                    Find tables related to a given table (by dataset name or table name) using the \
                    knowledge-base relation graph. Returns relation edges (shared columns, FK-style \
                    naming, or user-documented relations) plus suggested JOIN fragments. Call this \
                    BEFORE writing SQL that needs data from more than one table.\
                    """)
    public String findRelatedTables(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "table", description = "Dataset name or table name to expand from")
                    String table) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        if (table == null || table.isBlank()) {
            return "error: table must not be blank";
        }
        String rel =
                contextProvider == null
                        ? ""
                        : contextProvider.relationsFor(eff.ownerId(), table, eff.groupIds());
        if (rel == null || rel.isBlank()) {
            return "no relations found for table '"
                    + table
                    + "'. If the question needs multiple tables, check list_data_sources and"
                    + " describe_table for shared columns, or ask the user to document relations.";
        }
        return rel;
    }

    @Tool(
            name = "lookup_semantic",
            description =
                    """
                    Resolve a business term or metric (e.g. '流失率', 'GMV', '高价值客户') against the \
                    knowledge-base semantic graph. Returns the matched entity(ies) with their \
                    dependent fields resolved to table.column, calculation caliber (计算口径), \
                    granularity/range/example facts, and cross-table JOIN hints. Call this BEFORE \
                    writing SQL whenever the question uses business jargon or a named metric, so \
                    you query the exact columns and caliber instead of guessing.\
                    """)
    public String lookupSemantic(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "term", description = "Business term or metric name to resolve")
                    String term) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        if (term == null || term.isBlank()) {
            return "error: term must not be blank";
        }
        if (knowledgeGraph == null) {
            return "knowledge graph not available";
        }
        String ctx = knowledgeGraph.semanticContext(eff.ownerId(), term, eff.groupIds());
        if (ctx == null || ctx.isBlank()) {
            return "no semantic match for '"
                    + term
                    + "'. Fall back to list_data_sources + describe_table, or ask the user for the"
                    + " definition.";
        }
        return ctx;
    }

    @Tool(
            name = "read_knowledge",
            description =
                    """
                    Read the knowledge-base business document(s) for the current conversation's \
                    selected knowledge bases: calculation caliber (计算口径), KPI/考核 target \
                    values, business terms and inter-table relation notes that the user uploaded. \
                    Call this BEFORE answering any question about business definitions, targets, \
                    or caliber, and BEFORE concluding that such definitions do not exist.\
                    """)
    public String readKnowledge(DatasetScope scope, RuntimeContext rc) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        if (contextProvider == null) {
            return "no knowledge available";
        }
        String text = contextProvider.relationshipsText(eff.ownerId(), eff.groupIds());
        if (text == null || text.isBlank()) {
            return "no knowledge document in the selected knowledge base(s). Ask the user to"
                    + " upload one (KB → 知识文档) or provide the definition directly.";
        }
        return text;
    }

    @Tool(
            name = "render_chart",
            description =
                    """
                    Render a chart from a query result. Pass the columns and rows of the LAST \
                    run_sql_preview result together with the user's question. The chart type and \
                    the full ECharts option are built deterministically server-side from the data \
                    shape and the question — do NOT craft chart specs or options yourself. \
                    Returns a JSON payload {chart:"echarts", chartType, title, option} that the \
                    UI renders directly.\
                    """)
    public String renderChart(
            @ToolParam(
                            name = "question",
                            description = "The user question this chart answers",
                            required = false)
                    String question,
            @ToolParam(name = "columns", description = "Column names of the query result, in order")
                    List<String> columns,
            @ToolParam(
                            name = "rows",
                            description =
                                    "Result rows; each row is a list of cell values as strings")
                    List<List<String>> rows,
            @ToolParam(
                            name = "mark_line_value",
                            description =
                                    "Optional KPI target/reference value to draw as a dashed red"
                                            + " horizontal line (e.g. 20000000 from the knowledge"
                                            + " doc). Omit when there is no target.",
                            required = false)
                    String markLineValue,
            @ToolParam(
                            name = "mark_line_label",
                            description = "Optional label for the reference line, e.g. 日均目标2000万",
                            required = false)
                    String markLineLabel) {
        ChartBuilder.BuiltChart chart = ChartBuilder.build(columns, rows, question);
        if (chart == null) {
            return "error: data is not chartable (need >=1 numeric column and >1 row)";
        }
        if (markLineValue != null && !markLineValue.isBlank()) {
            try {
                double v = Double.parseDouble(markLineValue.trim().replace(",", ""));
                ChartBuilder.applyMarkLine(chart.option(), v, markLineLabel);
            } catch (NumberFormatException e) {
                // ignore unparsable target; chart still renders without the line
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chart", "echarts");
        payload.put("chartType", chart.chartType());
        payload.put("title", chart.title());
        if (chartOptions != null) {
            try {
                String chartId = java.util.UUID.randomUUID().toString();
                chartOptions.save(
                        new ChartOptionEntity(chartId, MAPPER.writeValueAsString(chart.option())));
                payload.put("chartId", chartId);
            } catch (JsonProcessingException e) {
                return "error: failed to serialize chart option";
            }
        } else {
            payload.put("option", chart.option());
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "error: failed to serialize chart payload";
        }
    }
}
