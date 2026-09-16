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
import io.agentscope.dataagent.ontology.OntologyService;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.semantic.model.CubeQuery;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.service.CubeQueryToSqlConverter;
import io.agentscope.dataagent.semantic.service.QueryHistoryService;
import io.agentscope.dataagent.semantic.service.SemanticModelService;
import io.agentscope.dataagent.semantic.service.SemanticQueryService;
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
    private final SessionAgentManager sessionAgents;
    private final OntologyService ontologyService;
    private final SemanticModelService semanticModelService;
    private final CubeQueryToSqlConverter cubeQueryConverter;
    private final QueryHistoryService queryHistoryService;
    private final SemanticQueryService semanticQueries;

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
        this(
                registry,
                sqlConnector,
                contextProvider,
                chartOptions,
                knowledgeGraph,
                conversationScopes,
                null);
    }

    public DataAgentToolkit(
            DataSourceRegistry registry,
            SqlConnector sqlConnector,
            DatasetContextProvider contextProvider,
            ChartOptionRepository chartOptions,
            KnowledgeGraphService knowledgeGraph,
            ConversationScopeRegistry conversationScopes,
            OntologyService ontologyService) {
        this(
                registry,
                sqlConnector,
                contextProvider,
                chartOptions,
                knowledgeGraph,
                conversationScopes,
                ontologyService,
                null,
                null,
                null);
    }

    public DataAgentToolkit(
            DataSourceRegistry registry,
            SqlConnector sqlConnector,
            DatasetContextProvider contextProvider,
            ChartOptionRepository chartOptions,
            KnowledgeGraphService knowledgeGraph,
            ConversationScopeRegistry conversationScopes,
            OntologyService ontologyService,
            SemanticModelService semanticModelService,
            CubeQueryToSqlConverter cubeQueryConverter,
            QueryHistoryService queryHistoryService) {
        this(
                registry,
                sqlConnector,
                contextProvider,
                chartOptions,
                knowledgeGraph,
                conversationScopes,
                ontologyService,
                semanticModelService,
                cubeQueryConverter,
                queryHistoryService,
                null,
                null);
    }

    public DataAgentToolkit(
            DataSourceRegistry registry,
            SqlConnector sqlConnector,
            DatasetContextProvider contextProvider,
            ChartOptionRepository chartOptions,
            KnowledgeGraphService knowledgeGraph,
            ConversationScopeRegistry conversationScopes,
            OntologyService ontologyService,
            SemanticModelService semanticModelService,
            CubeQueryToSqlConverter cubeQueryConverter,
            QueryHistoryService queryHistoryService,
            SessionAgentManager sessionAgents,
            SemanticQueryService semanticQueries) {
        this.semanticQueries = semanticQueries;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sqlConnector = Objects.requireNonNull(sqlConnector, "sqlConnector");
        this.contextProvider = contextProvider;
        this.chartOptions = chartOptions;
        this.knowledgeGraph = knowledgeGraph;
        this.conversationScopes = conversationScopes;
        this.ontologyService = ontologyService;
        this.semanticModelService = semanticModelService;
        this.cubeQueryConverter = cubeQueryConverter;
        this.queryHistoryService = queryHistoryService;
        this.sessionAgents = sessionAgents;
    }

    @Tool(
            name = "list_data_sources",
            description =
                    """
                    List the data sources the admin has configured for this deployment. Each entry \
                    is reported as 'id | kind | label — description (tags)'. Call this before \
                    drafting any SQL so you pick a source the runtime actually knows about. \
                    When an ontology summary is present, use search_model to locate specific \
                    business objects by name/label before writing SQL. \
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
            String ontology = contextProvider.ontologySummary(scope.ownerId(), scope.groupIds());
            if (ontology != null && !ontology.isBlank()) {
                out = out + "\n\n## 数据本体（对象目录）\n" + ontology;
            }
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
            if ((groups == null || groups.isEmpty()) && sessionAgents != null) {
                String gate = sessionAgents.gateKeyForSessionId(rc.getSessionId());
                if (gate != null) {
                    groups = conversationScopes.get(gate);
                }
            }
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
                                            && isMine(ds, scope)
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

    @Tool(
            name = "describe_ontology",
            description =
                    """
                    Describe the ontology model (objects, relationships, metrics, rules, \
                    limitations) for the current knowledge base. Call this when the user asks \
                    "有什么数据集" / "能查什么数据" or when you need to understand the business \
                    semantics before writing SQL. Returns a formatted text description. \
                    Returns 'none' when no ontology is configured.\
                    """)
    public String describeOntology(DatasetScope scope, RuntimeContext rc) {
        if (ontologyService == null) {
            return "none: ontology service not available";
        }
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        String groupId =
                eff.hasGroupFilter() && !eff.groupIds().isEmpty() ? eff.groupIds().get(0) : null;
        if (groupId == null) {
            return "none: no knowledge base selected. Ask the user to select one.";
        }
        return ontologyService.formatOntologyCatalog(eff.ownerId(), groupId);
    }

    @Tool(
            name = "show_ontology_graph",
            description =
                    """
                    Show the ontology graph (objects, relationships, metrics) as a visual \
                    diagram. Call this when the user wants to see the data model, or when a \
                    query involves multiple ontology objects and the user benefits from seeing \
                    their relationships. Returns a JSON payload {type:"ontology_graph", ...} \
                    that the UI renders as an interactive graph.\
                    """)
    public String showOntologyGraph(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "highlight_objects",
                            description =
                                    "Optional list of object names to highlight in the graph "
                                            + "(e.g. the objects involved in the current query)",
                            required = false)
                    List<String> highlightObjects) {
        if (ontologyService == null) {
            return "error: ontology service not available";
        }
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        String groupId =
                eff.hasGroupFilter() && !eff.groupIds().isEmpty() ? eff.groupIds().get(0) : null;
        if (groupId == null) {
            return "error: no knowledge base selected";
        }
        try {
            Map<String, Object> graphData =
                    highlightObjects != null && !highlightObjects.isEmpty()
                            ? ontologyService.getSubGraph(eff.ownerId(), groupId, highlightObjects)
                            : ontologyService.getOntologyGraph(eff.ownerId(), groupId);
            Map<String, Object> payload = new LinkedHashMap<>(graphData);
            payload.put("type", "ontology_graph");
            if (highlightObjects != null) {
                payload.put("highlight", highlightObjects);
            }
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "error: failed to build ontology graph: " + e.getMessage();
        }
    }

    @Tool(
            name = "search_model",
            description =
                    """
                    Search ontology objects, properties, and relationships by business name \
                    (Chinese label), table name, or description. Use this to locate the \
                    business objects relevant to the user's question before writing SQL. \
                    Returns matched objects with attribute summaries and relation edges. \
                    If no match, try list_data_sources to see the full object catalog.\
                    """)
    public String searchModel(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "query",
                            description =
                                    "Business name, table name, or keyword to search "
                                            + "(e.g. '用户', 'click_observation', '点击')")
                    String query) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        if (query == null || query.isBlank()) {
            return "error: query must not be blank";
        }
        if (contextProvider == null) {
            return "no ontology context available";
        }
        // Search across all visible groups
        String q = query.toLowerCase();
        StringBuilder sb = new StringBuilder();

        // Use ontologySummary to get compact catalog, then match
        String summary = contextProvider.ontologySummary(eff.ownerId(), eff.groupIds());
        if (summary == null || summary.isBlank()) {
            return "no ontology model configured. Call list_data_sources first to see available"
                    + " data sources, then ask the user to upload data or generate a model.";
        }

        // For each object, check if query matches name/label/description/property labels
        // Use ontologyModelText for matched objects to return full details
        boolean anyMatch = false;
        // Parse summary lines to find object names, then check each
        for (String line : summary.split("\n")) {
            if (line.contains("↔") || line.startsWith("…")) {
                continue; // skip relation lines and truncation markers
            }
            // Object line format: "name(label) [kind] — N relations"
            String objName = line.split("[\\(\\[]")[0].trim();
            if (objName.isEmpty()) {
                continue;
            }
            if (objName.toLowerCase().contains(q) || line.toLowerCase().contains(q)) {
                String detail =
                        contextProvider.ontologyModelText(eff.ownerId(), eff.groupIds(), objName);
                if (detail != null) {
                    if (anyMatch) {
                        sb.append('\n');
                    }
                    sb.append(detail);
                    anyMatch = true;
                }
            }
        }

        if (!anyMatch) {
            return "no match for '"
                    + query
                    + "'. Try list_data_sources to see the full catalog, or ask the user for"
                    + " the correct business term.";
        }
        return sb.toString();
    }

    @Tool(
            name = "get_model",
            description =
                    """
                    Get the full definition of a single ontology object: all attributes, \
                    relation edges with JOIN suggestions, and derived SQL (if applicable). \
                    Call this BEFORE writing SQL to understand the exact columns, types, \
                    and how to JOIN related tables. Pass the object name (e.g. 'app_user') \
                    or its Chinese label (e.g. '用户').\
                    """)
    public String getModel(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "object_name",
                            description =
                                    "Object name or Chinese label " + "(e.g. 'app_user' or '用户')")
                    String objectName) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        if (objectName == null || objectName.isBlank()) {
            return "error: object_name must not be blank";
        }
        if (contextProvider == null) {
            return "no ontology context available";
        }
        String text = contextProvider.ontologyModelText(eff.ownerId(), eff.groupIds(), objectName);
        if (text == null || text.isBlank()) {
            return "no object matching '"
                    + objectName
                    + "'. Use search_model to find available objects, or list_data_sources"
                    + " for the full catalog.";
        }
        return text;
    }

    // ====== 语义建模工具 ======

    @Tool(
            name = "list_models",
            description =
                    "发现当前用户/所选知识库中的逻辑语义模型，可按 query 搜索并分页。有模型时按 list_models →"
                        + " get_semantic_context/describe_model → recall_similar_queries → dry_plan"
                        + " → query_semantic 工作流问数；Cube 为可选路径。不要把物理表名当成逻辑名。")
    public String listModels(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "可选知识库 ID，多选查询时须指定", required = false)
                    String groupId,
            @ToolParam(name = "query", description = "模型关键词", required = false) String query,
            @ToolParam(name = "offset", description = "分页起点，默认 0", required = false) Integer offset,
            @ToolParam(name = "limit", description = "分页数量，默认 20，最多 100", required = false)
                    Integer limit) {
        if (semanticQueries == null)
            return SemanticQueryService.error("models", groupId, "语义服务不可用");
        return semanticQueries.listModels(
                effectiveScope(scope, rc), groupId, query, number(offset), number(limit));
    }

    @Tool(
            name = "get_semantic_context",
            description =
                    "读取语义模型、Cube 指标、规则和数据限制。小模型返回完整上下文，大模型按章节分页；必须补读未完整返回的规则/局限/说明。SQL"
                            + " 使用逻辑模型名；字段细节用 describe_model。")
    public String getSemanticContext(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；只有唯一候选时可省略", required = false)
                    String groupId,
            @ToolParam(
                            name = "section",
                            description =
                                    "overview/rules/limitations/instructions/relationships/cubes，默认"
                                            + " overview",
                            required = false)
                    String section,
            @ToolParam(name = "offset", description = "分页起点，默认 0", required = false) Integer offset,
            @ToolParam(name = "limit", description = "分页数量，默认 20", required = false)
                    Integer limit) {
        if (semanticQueries == null)
            return SemanticQueryService.error("context", groupId, "语义服务不可用");
        return semanticQueries.context(
                effectiveScope(scope, rc), groupId, section, number(offset), number(limit));
    }

    @Tool(
            name = "describe_model",
            description =
                    "读取逻辑模型的字段、计算表达式、关系和 Cube 口径；queryable=false 字段不能直接 SELECT，relationship handle"
                            + " 只供计算列展开。自动关系仅支持单跳 to-one。")
    public String describeModel(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "model_name", description = "list_models 返回的逻辑模型名") String name,
            @ToolParam(name = "group_id", description = "知识库 ID", required = false) String groupId,
            @ToolParam(name = "offset", description = "字段分页起点", required = false) Integer offset,
            @ToolParam(name = "limit", description = "字段分页数量", required = false) Integer limit) {
        if (semanticQueries == null) return SemanticQueryService.error("model", groupId, "语义服务不可用");
        return semanticQueries.describeModel(
                effectiveScope(scope, rc), groupId, name, number(offset), number(limit));
    }

    @Tool(
            name = "recall_similar_queries",
            description =
                    "召回当前模型/规则/绑定版本下执行成功的语义 SQL，最多 5 条；seed_unexecuted 为未执行参考，不代表正确结果。不要照搬旧物理 SQL"
                            + " 或对 UV 等字段擅自 SUM。")
    public String recallSimilarQueries(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "question", description = "用户的自然语言问题") String question,
            @ToolParam(name = "group_id", description = "知识库 ID", required = false)
                    String groupId) {
        if (semanticQueries == null)
            return SemanticQueryService.error("recall", groupId, "语义服务不可用");
        return semanticQueries.recall(effectiveScope(scope, rc), groupId, question);
    }

    @Tool(
            name = "dry_plan",
            description =
                    "将逻辑模型名 SELECT SQL"
                        + " 进行授权绑定及语义编译，不执行数据库、不取业务数据。通过仅表示语义编译通过，不表示物理数据库或业务口径已验证；失败必须修正，不回退到未受控的物理"
                        + " SQL。")
    public String dryPlan(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "sql", description = "使用逻辑模型名的单条只读 SELECT SQL") String sql,
            @ToolParam(name = "group_id", description = "知识库 ID", required = false)
                    String groupId) {
        if (semanticQueries == null) return SemanticQueryService.error("plan", groupId, "语义服务不可用");
        return semanticQueries.dryPlan(effectiveScope(scope, rc), groupId, sql);
    }

    @Tool(
            name = "query_semantic",
            description =
                    "执行语义 SQL，每次重新授权和编译。传原始逻辑模型 SQL，不能传 dry_plan 返回的"
                            + " compiledSql。返回结构化列/行、预览截断、耗时及来源；成功自动保存记忆，无需再 store_query。")
    public String querySemantic(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "sql", description = "原始语义 SQL，不是物理编译 SQL") String sql,
            @ToolParam(name = "question", description = "当前要回答的问题") String question,
            @ToolParam(name = "group_id", description = "知识库 ID", required = false) String groupId,
            @ToolParam(name = "row_limit", description = "预览行数，默认 20，最多 100", required = false)
                    Integer rowLimit) {
        if (semanticQueries == null) return SemanticQueryService.error("query", groupId, "语义服务不可用");
        return semanticQueries.query(
                effectiveScope(scope, rc), groupId, sql, question, number(rowLimit));
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }

    @Tool(
            name = "execute_cube_query",
            description =
                    """
                    执行结构化 Cube 查询（DSL 路径）：把 cube_query JSON 转换为物理 SQL 并返回，\
                    拿到 SQL 后再调用 run_sql_preview 执行取数。
                    queryJson 格式（cube/measures/dimensions 一律用字符串名称，measures/dimensions 是字符串数组）：
                    {"type":"cube_query","cube":"<cube名>","measures":["<度量名>"],\
                    "dimensions":["<维度名>"],\
                    "timeDimensions":[{"name":"<时间维度>","granularity":"day|week|month|quarter|year",\
                    "start":"YYYY-MM-DD","end":"YYYY-MM-DD"}],\
                    "filters":[{"dimension":"<维度>","operator":"eq|ne|gt|gte|lt|lte|in|not_in|like|between",\
                    "value":"<值>"}],\
                    "orderBy":[{"member":"<度量或维度>","direction":"asc|desc"}],"limit":100}
                    示例：{"type":"cube_query","cube":"click_metrics","measures":["click_pv","click_uv"],\
                    "dimensions":["module_name"],"orderBy":[{"member":"click_pv","direction":"desc"}],\
                    "limit":50}
                    度量/维度/时间维度的可用名称以 get_semantic_context 返回的 Cube 定义为准，禁止自造。\
                    """)
    public String executeCubeQuery(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "queryJson", description = "CubeQuery JSON 字符串") String queryJson,
            @ToolParam(name = "group_id", description = "知识库 ID", required = false)
                    String groupId) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        if (cubeQueryConverter == null || semanticModelService == null || semanticQueries == null) {
            return "error: 语义查询服务不可用";
        }
        if (queryJson == null || queryJson.isBlank()) {
            return "error: queryJson 参数不能为空";
        }
        try {
            String authorizedGroup = semanticQueries.resolveGroup(eff, groupId).getId();
            Optional<SemanticModel> opt =
                    semanticModelService.getModel(eff.ownerId(), authorizedGroup);
            if (opt.isEmpty()) {
                return "error: 当前知识库未配置语义模型，无法执行 Cube 查询";
            }
            CubeQuery query = MAPPER.readValue(queryJson, CubeQuery.class);
            String sql = cubeQueryConverter.convert(query, opt.get());
            try {
                return MAPPER.writeValueAsString(
                        new CubeQueryResult(query.getCube(), sql, "Cube 查询已转换为 SQL"));
            } catch (JsonProcessingException e) {
                return "Cube: " + query.getCube() + "\nSQL:\n" + sql;
            }
        } catch (IllegalArgumentException e) {
            return "error: Cube 查询转换失败 — " + e.getMessage();
        } catch (Exception e) {
            log.warn("execute_cube_query 失败", e);
            return "error: 查询解析失败 — " + e.getMessage();
        }
    }

    @Tool(
            name = "store_query",
            description =
                    """
                    存储一次成功的查询对（自然语言问题 + 结构化查询JSON + 生成的SQL），
                    供后续 recall_similar_queries 召回作为 few-shot 参考。
                    在查询执行成功并确认结果正确后调用。
                    """)
    public String storeQuery(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "question", description = "用户的自然语言问题") String question,
            @ToolParam(name = "queryJson", description = "结构化 CubeQuery JSON") String queryJson,
            @ToolParam(name = "sql", description = "生成的 SQL") String sql) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: no tenant context available";
        }
        if (queryHistoryService == null || semanticQueries == null) {
            return "error: 查询历史服务不可用";
        }
        String authorizedGroup;
        try {
            authorizedGroup = semanticQueries.resolveGroup(eff, null).getId();
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
        queryHistoryService.store(authorizedGroup, question, queryJson, sql);
        return "查询对已成功存储。";
    }

    /** Cube 查询转换结果。 */
    private record CubeQueryResult(String cube, String sql, String explanation) {}
}
