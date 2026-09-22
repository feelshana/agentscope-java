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

    /** Maximum number of tables accepted by one batch prepare_data_context call. */
    private static final int MAX_PREPARE_TABLES = 5;

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

    // -----------------------------------------------------------------
    //  TC-style tools: prepare_data_context, query_structured_data,
    //  retrieve_evidence, render_chart
    //
    //  Data source / knowledge base overview is injected into the system
    //  prompt by DataDynamicContextMiddleware — no longer needs a tool call.
    // -----------------------------------------------------------------

    @Tool(
            name = "prepare_data_context",
            description =
                    """
                    查看当前可用数据源中表的描述和完整列结构（列名、类型、AI 生成的字段描述）。\
                    在调用 query_structured_data 之前，如果需要确认列名拼写、\
                    字段类型、日期格式、枚举值等细节，先调用本工具。\
                    与问题直接相关的 1-3 张表用 tables 参数一次批量取回（最多 5 张），\
                    不要一张一张分开调用，也不要漏掉直接相关的表而盲写列名；\
                    只确认单表时改用 source_id + table 两个参数。\
                    输出的字段描述通常已含枚举与取值提示（如「包含领导等值」），\
                    据此直接写 WHERE，无需再探查取值。\
                    """)
    public String prepareDataContext(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "source_id",
                            description = "数据源 ID，从 [DATA_SOURCES_OVERVIEW] 中获取；传了 tables 时省略",
                            required = false)
                    String sourceId,
            @ToolParam(name = "table", description = "表名；传了 tables 时省略", required = false)
                    String table,
            @ToolParam(
                            name = "tables",
                            description =
                                    "可选，批量表清单，元素为 {source_id, table}，最多 5 张；传入后以 tables 为准，忽略"
                                            + " source_id/table",
                            required = false)
                    List<Map<String, String>> tables) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (tables != null && !tables.isEmpty()) {
            return prepareDataContextBatch(eff, tables);
        }
        if (sourceId == null || sourceId.isBlank()) {
            return "error: 缺少参数：批量取表请传 tables（{source_id, table} 数组，最多 5 张）；"
                    + "单表查询请传 source_id + table";
        }
        Optional<DataSource> ds = resolveScoped(eff, sourceId);
        if (ds.isEmpty()) {
            return "error: 未知或不 permitted 的 source_id '" + sourceId + "'";
        }
        if (table == null || table.isBlank()) {
            return "error: table 不能为空";
        }
        if (!sqlConnector.supports(ds.get())) {
            return "error: 数据源类型 '" + ds.get().kind() + "' 不支持 SQL 查询";
        }

        // Build context from stored metadata — no DB query needed.
        return "# " + ds.get().label() + "\n\n" + buildTableSection(ds.get());
    }

    /**
     * Backward-compatible single-table form without the batch parameter; delegates to the
     * annotated tool method.
     */
    public String prepareDataContext(
            DatasetScope scope, RuntimeContext rc, String sourceId, String table) {
        return prepareDataContext(scope, rc, sourceId, table, null);
    }

    /**
     * Batch mode of {@code prepare_data_context}: one {@code ## <label>} section per requested
     * table. Each entry is validated independently (visibility via {@link #resolveScoped},
     * non-blank table, connector support) — a failing entry produces an inline error section in
     * its position without affecting the other entries and never leaks that table's column
     * information. More than {@value #MAX_PREPARE_TABLES} tables are rejected outright with no
     * partial execution.
     */
    private String prepareDataContextBatch(DatasetScope eff, List<Map<String, String>> tables) {
        if (tables.size() > MAX_PREPARE_TABLES) {
            return "error: tables 最多 "
                    + MAX_PREPARE_TABLES
                    + " 张，请精简到 "
                    + MAX_PREPARE_TABLES
                    + " 张以内后重试";
        }
        List<String> sections = new java.util.ArrayList<>();
        for (Map<String, String> entry : tables) {
            String sourceId = entry == null ? null : entry.get("source_id");
            String table = entry == null ? null : entry.get("table");
            Optional<DataSource> ds =
                    sourceId == null ? Optional.empty() : resolveScoped(eff, sourceId);
            if (ds.isEmpty()) {
                sections.add(
                        "## "
                                + sourceId
                                + "\n\nerror: 未知或不 permitted 的 source_id '"
                                + sourceId
                                + "'");
                continue;
            }
            if (table == null || table.isBlank()) {
                sections.add("## " + ds.get().label() + "\n\nerror: table 不能为空");
                continue;
            }
            if (!sqlConnector.supports(ds.get())) {
                sections.add(
                        "## "
                                + ds.get().label()
                                + "\n\nerror: 数据源类型 '"
                                + ds.get().kind()
                                + "' 不支持 SQL 查询");
                continue;
            }
            sections.add(
                    "## "
                            + ds.get().label()
                            + "\n\n"
                            + buildTableSection(ds.get()).stripTrailing());
        }
        return String.join("\n\n", sections);
    }

    /**
     * Renders the per-table section body (table description + column schema table) from stored
     * metadata — no DB query needed. Only column name/original name/type/description are
     * included (no bulky sample values), keeping batch output within the toolResultEviction
     * budget. The concatenation order is kept byte-identical to the legacy single-table output.
     */
    private String buildTableSection(DataSource ds) {
        StringBuilder sb = new StringBuilder();

        // Table description
        String desc = ds.description();
        if (desc != null && !desc.isBlank()) {
            sb.append(desc).append("\n\n");
        }

        // Column schema with descriptions and dimension examples
        String columnSchemaJson =
                ds.properties() != null ? ds.properties().get("columnSchemaJson") : null;
        if (columnSchemaJson != null && !columnSchemaJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> columns = MAPPER.readValue(columnSchemaJson, List.class);
                if (!columns.isEmpty()) {
                    sb.append("### 字段信息\n\n");
                    sb.append("| 英文名 | 原始名 | 类型 | 描述 |\n");
                    sb.append("|---|---|---|---|\n");
                    for (Map<String, Object> col : columns) {
                        String name = String.valueOf(col.getOrDefault("name", ""));
                        String originalName = String.valueOf(col.getOrDefault("originalName", ""));
                        String sqlType = String.valueOf(col.getOrDefault("sqlType", ""));
                        String description = String.valueOf(col.getOrDefault("description", ""));
                        sb.append("| ")
                                .append(name)
                                .append(" | ")
                                .append(originalName)
                                .append(" | ")
                                .append(sqlType)
                                .append(" | ")
                                .append(description)
                                .append(" |\n");
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse columnSchemaJson: {}", e.getMessage());
            }
        }
        return sb.toString();
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
                return "error: 查询引用了不属于您的数据表: " + m.group();
            }
        }
        return null;
    }

    @Tool(
            name = "query_structured_data",
            description =
                    """
                    执行只读 SQL 查询并返回结果。前提：[DATA_SOURCES_OVERVIEW] 存在且列出了可用表。\
                    调用前应先通过 prepare_data_context 确认列名和字段类型。\
                    只接受 SELECT / WITH 语句。返回结果包含 SQL 语句、执行结果和数据预览。\
                    多表关联必须在同一条 SQL 内用 JOIN/CTE 完成，禁止把上一步查询结果作为字面量复制进 IN (...)——\
                    跨表筛选和补维度属性（部门、职位等）都算，需要上一步结果里的值时一律改用 JOIN/CTE 在同一条 SQL 内取。\
                    JOIN 前先判断维表粒度：一个关联键对应多行时（如用户×项目权限表）必须先 \
                    WITH u AS (SELECT DISTINCT 关联键, 所需列 FROM 维表) 去重再 JOIN 明细表，\
                    防止扇出导致 COUNT/SUM 被放大。\
                    不要发仅用于了解数据规模、日期范围或取值分布的探查查询\
                    （COUNT(*) / MIN / MAX / 无筛选的全表 GROUP BY 分布统计）；\
                    结论本身需要的聚合不在此列。\
                    详细写法与反模式见 sql-analysis 技能。\
                    """)
    public String queryStructuredData(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "source_id", description = "数据源 ID，从 [DATA_SOURCES_OVERVIEW] 中获取")
                    String sourceId,
            @ToolParam(name = "sql", description = "SELECT-only SQL 语句") String sql,
            @ToolParam(
                            name = "question",
                            description = "自然语言描述本次查询的业务问题（中文），用于结果自文档化",
                            required = false)
                    String question,
            @ToolParam(name = "row_limit", description = "最大返回行数", required = false)
                    Integer rowLimit) {
        DatasetScope eff = effectiveScope(scope, rc);
        Optional<DataSource> ds = resolveScoped(eff, sourceId);
        if (ds.isEmpty()) {
            return "error: 未知或不 permitted 的 source_id '"
                    + sourceId
                    + "'。请先查看 [DATA_SOURCES_OVERVIEW] 中的可用数据源。";
        }
        if (sql == null || sql.isBlank()) {
            return "error: sql 不能为空";
        }
        String trimmed = sql.trim().toLowerCase();
        if (!trimmed.startsWith("select") && !trimmed.startsWith("with")) {
            return "error: 只允许 SELECT / WITH 语句";
        }
        String crossTable = checkCrossTable(eff, sql);
        if (crossTable != null) {
            return crossTable;
        }
        if (!sqlConnector.supports(ds.get())) {
            return "error: 数据源类型 '" + ds.get().kind() + "' 不支持 SQL 查询";
        }
        String result =
                sqlConnector.runSqlPreview(
                        ds.get(), sql, question, rowLimit != null ? rowLimit : 0);

        // runSqlPreview already includes question, SQL, and result table —
        // just prepend a section header to avoid duplicating them.
        return "## 查询结果\n\n" + result;
    }

    @Tool(
            name = "retrieve_evidence",
            description =
                    """
                    从知识库中检索与用户问题相关的文档片段。\
                    当用户问知识、文档、政策、概念定义、制度、口径、说明、解释类问题时使用。\
                    也可以用于数据源不可达时从知识库中获取相关数据。\
                    query 应使用 10-30 字精炼关键词，保留核心实体和业务名词。\
                    """)
    public String retrieveEvidence(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "query", description = "检索关键词，10-30 字精炼") String query) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: 无法确定租户上下文";
        }
        if (contextProvider == null) {
            return "no knowledge available";
        }
        String text = contextProvider.relationshipsText(eff.ownerId(), eff.groupIds());
        if (text == null || text.isBlank()) {
            return "知识库中没有与 '" + query + "' 相关的内容。";
        }
        return "## 检索结果\n\n" + text;
    }

    @Tool(
            name = "render_chart",
            description =
                    """
                    渲染图表。传入查询结果的列和数据，图表类型由服务端根据数据形态自动推断。\
                    返回 JSON payload {chart:"echarts", chartType, title, option} 供 UI 渲染。\
                    不要自己构造图表配置，只传数据即可。\
                    """)
    public String renderChart(
            @ToolParam(name = "question", description = "本图表回答的用户问题（中文）", required = false)
                    String question,
            @ToolParam(name = "columns", description = "查询结果的列名，按顺序排列") List<String> columns,
            @ToolParam(name = "rows", description = "结果数据行；每行是一个列表，单元格值为字符串")
                    List<List<String>> rows,
            @ToolParam(
                            name = "mark_line_value",
                            description =
                                    "可选的 KPI 目标/参考值，以红色虚线水平线呈现" + "（如知识文档中的 20000000）。无目标时省略。",
                            required = false)
                    String markLineValue,
            @ToolParam(
                            name = "mark_line_label",
                            description = "参考线的标签，如 日均目标2000万",
                            required = false)
                    String markLineLabel) {
        ChartBuilder.BuiltChart chart = ChartBuilder.build(columns, rows, question);
        if (chart == null) {
            return "error: 数据不适合绘制图表（需要至少1列数值且多于1行）";
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
                return "error: 序列化图表配置失败";
            }
        } else {
            payload.put("option", chart.option());
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "error: 序列化图表数据失败";
        }
    }
}
