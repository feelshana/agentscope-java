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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway.WrenCallResult;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent-facing toolkit for the single wren semantic query channel. It executes logical SQL and
 * cube queries and exposes scoped, on-demand model metadata without leaking physical source names.
 * Groups with a valid published manifest remain queryable while semantic drafts are pending; groups
 * without a valid snapshot are rejected instead of falling back to physical SQL.
 *
 * <p>Registered onto the main {@code data-agent} at startup by {@link DataToolkitRegistrar}.
 *
 * <p>Successful query results are also persisted as CSV files inside the session's sandbox
 * workspace (specs/016, ADR 0030): the returned text references the file path so {@code
 * run_python} reads it with {@code pd.read_csv} instead of the model copying rows through the
 * context window. The handoff is best-effort — a failed write degrades to the plain markdown
 * table and never fails the query.
 */
public final class WrenToolkit {

    private static final Logger log = LoggerFactory.getLogger(WrenToolkit.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum cell length in the markdown result preview. */
    private static final int CELL_TRUNCATION = 200;

    private static final int MAX_DESCRIBE_MODELS = 5;
    private static final int MAX_DESCRIBE_CHARS = 20000;
    private static final String STATE_DIRTY = "DIRTY";

    /** Granularities accepted by the wren cube time-dimension spec. */
    private static final Set<String> TIME_GRANULARITIES =
            Set.of("year", "quarter", "month", "week", "day", "hour", "minute");

    /** Upper bound for the persisted CSV; larger results skip the file handoff. */
    private static final int MAX_DATA_FILE_BYTES = 10 * 1024 * 1024;

    /** Session workspace root shared with {@link RunPythonTool} — code runs with cwd here. */
    private static final String RUNPYTHON_DIR = "/workspace/runpython/";

    /**
     * wrenai's mysql connector appends its own row limit to the tail of every SQL (source-verified
     * {@code _apply_limit}, ADR 0021), so an explicit LIMIT/OFFSET clause in the SQL would be
     * duplicated into a 1064 syntax error — caught here for a self-correctable message instead.
     */
    private static final Pattern EXPLICIT_LIMIT = Pattern.compile("(?i)\\b(limit|offset)\\b");

    private final WrenQueryGateway wrenGateway;
    private final DatasetGroupService groupService;
    private final ConversationScopeRegistry conversationScopes;
    private final MdlCatalog mdlCatalog;

    /** Optional sandbox proxy for the CSV data handoff; {@code null} disables it entirely. */
    private final AbstractSandboxFilesystem sandboxFilesystem;

    public WrenToolkit(
            WrenQueryGateway wrenGateway,
            DatasetGroupService groupService,
            ConversationScopeRegistry conversationScopes,
            MdlCatalog mdlCatalog) {
        this(wrenGateway, groupService, conversationScopes, mdlCatalog, null);
    }

    /**
     * Full wiring used by {@link DataToolkitRegistrar}. The filesystem is a standalone {@link
     * AbstractSandboxFilesystem} proxy — the live per-user sandbox is bound on each call's {@link
     * RuntimeContext} by the sandbox lifecycle middleware, exactly like {@link RunPythonTool}.
     */
    public WrenToolkit(
            WrenQueryGateway wrenGateway,
            DatasetGroupService groupService,
            ConversationScopeRegistry conversationScopes,
            MdlCatalog mdlCatalog,
            AbstractSandboxFilesystem sandboxFilesystem) {
        this.wrenGateway = Objects.requireNonNull(wrenGateway, "wrenGateway");
        this.groupService = Objects.requireNonNull(groupService, "groupService");
        this.conversationScopes = conversationScopes;
        this.mdlCatalog = Objects.requireNonNull(mdlCatalog, "mdlCatalog");
        this.sandboxFilesystem = sandboxFilesystem;
    }

    // -----------------------------------------------------------------
    //  wren_run_sql
    // -----------------------------------------------------------------

    @Tool(
            name = "wren_run_sql",
            description =
                    """
                    在已发布语义模型的知识库上执行 SQL（wren 语义引擎，在逻辑模型或已发布 View 上查询）；知识库和逻辑模型必须取自 [DATA_SOURCES_OVERVIEW]。\
                    group_id 参数优先直接传知识库名称（推荐）；也可传完整 group_id，必须逐字符原样复制。\
                    聚合指标问题先核对 Cube 清单，Cube 成员能覆盖时优先用 wren_query_cube（引擎确定性编译聚合，错误率更低）；\
                    已发布 View 能直接覆盖问题时优先直接按视图名查询（视图口径已经建模审阅）。\
                    字段或关联组不明确时先调用 wren_describe_model；跨模型属性优先查询 many 侧模型的关联投影列，让 Wren 自动 JOIN。\
                    仅当语义资产都无法表达问题时才编写其他逻辑 SQL；显式 JOIN 是最后兜底。\
                    只接受 SELECT / WITH 语句；表名和列名使用语义模型中的逻辑名称，不要用物理表名。\
                    SQL 内禁止写显式 LIMIT/OFFSET 子句：引擎会自动在 SQL 末尾追加行数上限，\
                    显式 LIMIT 会与之叠加成语法错误；控制返回行数请用 limit 参数。\
                    没有有效已发布 MDL 的知识库不可查询，请先完成基础 MDL 初始化。\
                    """)
    public String wrenRunSql(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "group_id",
                            description =
                                    "知识库标识：优先直接传 [DATA_SOURCES_OVERVIEW] 中该知识库的名称（推荐，如「666」）；"
                                            + "也可传该段落的 group_id（36 位 UUID，必须逐字符原样复制）")
                    String groupId,
            @ToolParam(
                            name = "sql",
                            description =
                                    "SELECT-only SQL，表名/列名用语义模型中的逻辑名称；不要包含 LIMIT/OFFSET 子句（行数用"
                                            + " limit 参数控制）")
                    String sql,
            @ToolParam(
                            name = "question",
                            description = "自然语言描述本次查询的业务问题（中文），用于结果自文档化",
                            required = false)
                    String question,
            @ToolParam(name = "limit", description = "最大返回行数（默认 1000，上限 10000）", required = false)
                    Integer limit) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: 无法确定租户上下文";
        }
        GroupRef ref = resolveGroup(eff, groupId);
        if (ref.error() != null) {
            return ref.error();
        }
        if (sql == null || sql.isBlank()) {
            return "error: sql 不能为空";
        }
        String trimmed = sql.trim().toLowerCase();
        if (!trimmed.startsWith("select") && !trimmed.startsWith("with")) {
            return "error: 只允许 SELECT / WITH 语句";
        }
        if (EXPLICIT_LIMIT.matcher(sql).find()) {
            return "error: SQL 中禁止显式 LIMIT/OFFSET 子句（wren 引擎会自动在 SQL 末尾追加行数上限，"
                    + "叠加会产生语法错误）。请删除 SQL 中的 LIMIT/OFFSET，改用本工具的 limit 参数控制返回行数。";
        }
        if (limit != null && limit < 0) {
            return "error: limit 不能为负数";
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sql", sql);
        if (limit != null) {
            args.put("limit", limit);
        }
        WrenCallResult result;
        try {
            result = wrenGateway.call(ref.group().getId(), "run_sql", args);
        } catch (DatasetException e) {
            return "error: " + e.getMessage();
        }
        if (!result.ok()) {
            return "error: wren 语义引擎拒绝了该查询：" + result.payload();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## wren 语义查询结果\n\n");
        sb.append(groupHeader(ref.group()));
        if (question != null && !question.isBlank()) {
            sb.append("**查询问题：** ").append(question.trim()).append("\n\n");
        }
        sb.append("**SQL 语句：**\n```sql\n").append(sql).append("\n```\n\n");
        sb.append(renderTable(result.payload()));
        sb.append(dataFileNote(rc, result.payload()));
        sb.append(dirtyNote(ref.group()));
        return sb.toString();
    }

    // -----------------------------------------------------------------
    //  wren_query_cube
    // -----------------------------------------------------------------

    @Tool(
            name = "wren_query_cube",
            description =
                    """
                    在已发布语义模型的知识库上执行 Cube（指标）聚合查询，返回聚合行。\
                    聚合问题先确认覆盖（参考 [DATA_SOURCES_OVERVIEW] 的 Cube 清单，必要时 wren_cube_describe 核对成员），覆盖时优先用本工具——引擎确定性编译 GROUP BY 与时间粒度，比手工聚合 SQL 错误率更低。\
                    cube、度量、维度的名称以 [DATA_SOURCES_OVERVIEW] 中该知识库的 Cube 清单为准，不要自行编造。\
                    group_id 参数优先直接传知识库名称（推荐）；也可传完整 group_id，必须逐字符原样复制。\
                    筛选格式 dim:op[:value]（in/not_in 用逗号分隔多值）；时间维度格式 name:granularity[:start,end]，\
                    granularity 使用小写 year/quarter/month/week/day/hour/minute；网关也会统一转成小写。\
                    时间区间为左闭右开，end 不含在内：start=end 会得到空结果——查单个自然日 D 必须传次日作 end，\
                    如查 2026-09-29 全天应传 2026-09-29,2026-09-30；查某月应传 2026-09-01,2026-10-01；\
                    仅传 name:granularity 不带区间则按全时间聚合。\
                    排序格式 member:direction（asc/desc），成员必须是本次查询已选中的原始成员名——度量/维度写其名称，\
                    时间维度写原始名（如 order_time；即使结果列显示为 order_time__month 也写 order_time）；\
                    未传 order_by 时引擎默认按时间升序（最早在前），取最近 N 期请用 order_by ["<时间维度名>:desc"] + limit。\
                    没有有效已发布 MDL 的知识库不可查询，请先完成基础 MDL 初始化。\
                    """)
    public String wrenQueryCube(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "group_id",
                            description =
                                    "知识库标识：优先直接传 [DATA_SOURCES_OVERVIEW] 中该知识库的名称（推荐，如「666」）；"
                                            + "也可传该段落的 group_id（36 位 UUID，必须逐字符原样复制）")
                    String groupId,
            @ToolParam(name = "cube", description = "Cube（指标集）名称，取自知识库的 Cube 清单") String cube,
            @ToolParam(name = "measures", description = "度量名称列表，至少 1 个") List<String> measures,
            @ToolParam(name = "dimensions", description = "分组维度名称列表，可选", required = false)
                    List<String> dimensions,
            @ToolParam(
                            name = "time_dimension",
                            description = "时间维度，格式 name:granularity[:start,end]，可选",
                            required = false)
                    String timeDimension,
            @ToolParam(
                            name = "filters",
                            description = "筛选条件列表，格式 dim:op[:value]，如 状态:eq:已完成；可选",
                            required = false)
                    List<String> filters,
            @ToolParam(
                            name = "order_by",
                            description =
                                    "排序列表，格式 member:direction，如 收入:desc；时间维度用原始名（如"
                                            + " order_time），不要写结果输出列别名（如 order_time__month）；可选",
                            required = false)
                    List<String> orderBy,
            @ToolParam(
                            name = "limit",
                            description = "最大返回行数，配合 order_by 取 Top-N；可选",
                            required = false)
                    Integer limit,
            @ToolParam(name = "offset", description = "分页偏移量，可选", required = false)
                    Integer offset) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: 无法确定租户上下文";
        }
        GroupRef ref = resolveGroup(eff, groupId);
        if (ref.error() != null) {
            return ref.error();
        }
        if (cube == null || cube.isBlank()) {
            return "error: cube 不能为空";
        }
        if (measures == null || measures.isEmpty()) {
            return "error: measures 至少需要 1 个度量";
        }
        if (limit != null && limit < 0) {
            return "error: limit 不能为负数";
        }
        if (offset != null && offset < 0) {
            return "error: offset 不能为负数";
        }
        // The model passes calendar ranges with inclusive-looking endpoints (SQL BETWEEN
        // intuition); wren-core compiles the end as exclusive, so a same-day spec would
        // silently degrade into an always-empty predicate. Widen it before forwarding —
        // and before echoing it back, so the model sees the corrected spec in-context.
        if (timeDimension != null && !timeDimension.isBlank()) {
            timeDimension = normalizeTimeDimension(timeDimension);
        }
        // The model routinely order-bys the alias it saw in a previous result header
        // (order_time__month); wren-core validates order-by members against the raw names
        // selected by the query, so rewrite the exact output-alias form back to the raw name
        // before forwarding — mirroring the normalizeTimeDimension gateway fix. Anything left
        // over stays untouched and is covered by the guided error below.
        orderBy = normalizeOrderBy(orderBy, timeDimension);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("cube", cube);
        args.put("measures", measures);
        if (dimensions != null && !dimensions.isEmpty()) {
            args.put("dimensions", dimensions);
        }
        if (timeDimension != null && !timeDimension.isBlank()) {
            args.put("time_dimension", timeDimension);
        }
        if (filters != null && !filters.isEmpty()) {
            args.put("filters", filters);
        }
        if (orderBy != null && !orderBy.isEmpty()) {
            args.put("order_by", orderBy);
        }
        if (limit != null) {
            args.put("limit", limit);
        }
        if (offset != null) {
            args.put("offset", offset);
        }
        WrenCallResult result;
        try {
            result = wrenGateway.call(ref.group().getId(), "query_cube", args);
        } catch (DatasetException e) {
            return "error: " + e.getMessage();
        }
        if (!result.ok()) {
            String payload = result.payload();
            if (payload != null && payload.contains("Cannot order by member")) {
                return "error: wren 语义引擎拒绝了该 Cube 查询："
                        + payload
                        + "\n提示：排序成员必须是本次查询已选中的原始成员名（measures / dimensions /"
                        + " time_dimension 的原始名）；时间维度排序写原始名（如 order_time），不要写"
                        + " 结果输出列别名（如 order_time__month）。取最近 N 期请用"
                        + " order_by [\"<时间维度名>:desc\"] + limit。";
            }
            return "error: wren 语义引擎拒绝了该 Cube 查询：" + payload;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## wren Cube 查询结果\n\n");
        sb.append(groupHeader(ref.group()));
        sb.append("**Cube：** ").append(cube).append('\n');
        sb.append("**度量：** ").append(String.join(", ", measures)).append('\n');
        if (dimensions != null && !dimensions.isEmpty()) {
            sb.append("**维度：** ").append(String.join(", ", dimensions)).append('\n');
        }
        if (timeDimension != null && !timeDimension.isBlank()) {
            sb.append("**时间维度：** ").append(timeDimension).append('\n');
        }
        if (filters != null && !filters.isEmpty()) {
            sb.append("**筛选：** ").append(String.join(", ", filters)).append('\n');
        }
        sb.append('\n');
        sb.append(renderTable(result.payload()));
        sb.append(dataFileNote(rc, result.payload()));
        sb.append(dirtyNote(ref.group()));
        return sb.toString();
    }

    @Tool(
            name = "wren_describe_model",
            description =
                    "按需查看已发布逻辑模型的字段、关系和相关 Cube。关系投影默认折叠；需要具体跨模型字段时传"
                            + " expand_relation_fields=true。只传逻辑模型名，不要传 datasetId、sourceId、schema"
                            + " 或物理表名；不要传视图名或 Cube 名（视图请直接 wren_run_sql，Cube 请用"
                            + " wren_query_cube）。group_id 参数优先直接传知识库名称（推荐）；也可传完整"
                            + " group_id，必须逐字符原样复制")
    public String wrenDescribeModel(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "group_id",
                            description =
                                    "知识库标识：优先直接传 [DATA_SOURCES_OVERVIEW] 中该知识库的名称（推荐）；"
                                            + "也可传完整的 group_id（36 位 UUID，必须逐字符原样复制）")
                    String groupId,
            @ToolParam(name = "model_names", description = "逻辑模型名列表，最多 5 个")
                    List<String> modelNames,
            @ToolParam(
                            name = "expand_relation_fields",
                            description = "是否展开全部关联投影字段；默认 false",
                            required = false)
                    Boolean expandRelationFields) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return "error: 无法确定租户上下文";
        }
        GroupRef ref = resolveGroup(eff, groupId);
        if (ref.error() != null) {
            return ref.error();
        }
        if (modelNames == null || modelNames.isEmpty()) {
            return "error: model_names 至少需要 1 个逻辑模型名";
        }
        if (modelNames.size() > MAX_DESCRIBE_MODELS) {
            return "error: model_names 最多允许 " + MAX_DESCRIBE_MODELS + " 个逻辑模型名";
        }
        MdlCatalog.GroupMdl mdl = mdlCatalog.load(ref.group().getId()).orElse(null);
        if (mdl == null) {
            return unavailableGroup(ref.group());
        }
        Map<String, MdlCatalog.Model> byName = new LinkedHashMap<>();
        for (MdlCatalog.Model model : mdl.models()) {
            byName.put(model.name(), model);
        }
        for (String name : modelNames) {
            if (name == null || name.isBlank() || !byName.containsKey(name)) {
                return unknownModelMessage(mdl, name);
            }
        }

        StringBuilder out = new StringBuilder("## 已发布语义模型详情\n\n");
        out.append(groupHeader(ref.group()));
        for (String name : new java.util.LinkedHashSet<>(modelNames)) {
            appendModelDescription(
                    out, byName.get(name), mdl, Boolean.TRUE.equals(expandRelationFields));
            if (out.length() > MAX_DESCRIBE_CHARS) {
                return out.substring(0, MAX_DESCRIBE_CHARS) + "\n\n*(内容已截断，请减少 model_names 后重试)*";
            }
        }
        out.append(dirtyNote(ref.group()));
        return out.toString();
    }

    /** Compatibility overload for callers compiled against the pre-folding method signature. */
    public String wrenDescribeModel(
            DatasetScope scope, RuntimeContext rc, String groupId, List<String> modelNames) {
        return wrenDescribeModel(scope, rc, groupId, modelNames, false);
    }

    /**
     * Guides the model back to the right tool when a rejected name actually belongs to a
     * published View or Cube (specs/020): without routing, the model retries describe on the
     * same names in a loop (2026-10-04 LLM.log). Unknown names keep the original error so a
     * wrong-name probe learns nothing about other groups' assets.
     */
    private static String unknownModelMessage(MdlCatalog.GroupMdl mdl, String name) {
        if (name != null && !name.isBlank()) {
            boolean isView = mdl.views().stream().anyMatch(view -> name.equals(view.name()));
            if (isView) {
                return "error: '"
                        + name
                        + "' 是已发布视图而非逻辑模型，请直接用 wren_run_sql 按视图名查询（FROM "
                        + name
                        + "），无需 describe";
            }
            boolean isCube = mdl.cubes().stream().anyMatch(cube -> name.equals(cube.name()));
            if (isCube) {
                return "error: '" + name + "' 是 Cube 而非逻辑模型，请用 wren_query_cube 按度量/维度查询";
            }
        }
        return "error: 未知或无权访问的已发布逻辑模型";
    }

    private static void appendModelDescription(
            StringBuilder out,
            MdlCatalog.Model model,
            MdlCatalog.GroupMdl mdl,
            boolean expandRelationFields) {
        out.append("### ").append(model.name()).append('\n');
        if (model.description() != null && !model.description().isBlank()) {
            out.append(model.description()).append("\n\n");
        }
        Map<String, MdlCatalog.Column> relationAliases = new LinkedHashMap<>();
        for (MdlCatalog.Column column : model.columns()) {
            if (!column.calculated()
                    && mdl.models().stream().anyMatch(m -> m.name().equals(column.type()))) {
                relationAliases.put(column.name(), column);
            }
        }
        Map<String, List<MdlCatalog.Column>> relationFields = new LinkedHashMap<>();
        for (MdlCatalog.Column column : model.columns()) {
            String expression = column.expression();
            if (!column.calculated() || expression == null) {
                continue;
            }
            int dot = expression.indexOf('.');
            if (dot > 0) {
                String alias = expression.substring(0, dot);
                if (relationAliases.containsKey(alias)) {
                    relationFields
                            .computeIfAbsent(alias, ignored -> new java.util.ArrayList<>())
                            .add(column);
                }
            }
        }

        out.append("| 字段 | 类型 | 描述 |\n|---|---|---|\n");
        for (MdlCatalog.Column column : model.columns()) {
            boolean relationAlias = relationAliases.containsKey(column.name());
            boolean relationProjection =
                    relationFields.values().stream().anyMatch(fields -> fields.contains(column));
            if (relationAlias || relationProjection) {
                continue;
            }
            appendColumnRow(out, column, false);
        }
        for (Map.Entry<String, List<MdlCatalog.Column>> entry : relationFields.entrySet()) {
            MdlCatalog.Column alias = relationAliases.get(entry.getKey());
            List<MdlCatalog.Column> fields = entry.getValue();
            out.append("| 关联字段组：")
                    .append(entry.getKey())
                    .append(" → ")
                    .append(alias.type())
                    .append(" | ")
                    .append(fields.size())
                    .append(" 个字段 | Wren 将按 relationship condition 自动 JOIN")
                    .append(
                            expandRelationFields
                                    ? "（已展开如下）"
                                    : "；需要列名时设置 expand_relation_fields=true")
                    .append(" |\n");
            if (expandRelationFields) {
                for (MdlCatalog.Column field : fields) {
                    appendColumnRow(out, field, true);
                }
            }
        }
        List<MdlCatalog.Relation> relations =
                mdl.relations().stream()
                        .filter(
                                relation ->
                                        model.name().equals(relation.leftModel())
                                                || model.name().equals(relation.rightModel()))
                        .toList();
        if (!relations.isEmpty()) {
            out.append("\n**关系：**\n");
            for (MdlCatalog.Relation relation : relations) {
                out.append("- ")
                        .append(relation.leftModel())
                        .append(" ")
                        .append(relation.joinType())
                        .append(" ")
                        .append(relation.rightModel())
                        .append("：")
                        .append(relation.condition())
                        .append('\n');
            }
        }
        List<MdlCatalog.Cube> cubes =
                mdl.cubes().stream().filter(cube -> model.name().equals(cube.baseModel())).toList();
        if (!cubes.isEmpty()) {
            out.append("\n**相关 Cube：**\n");
            for (MdlCatalog.Cube cube : cubes) {
                out.append("- ")
                        .append(cube.name())
                        .append("（度量：")
                        .append(cube.measures().stream().map(MdlCatalog.Member::name).toList())
                        .append("；维度：")
                        .append(cube.dimensions().stream().map(MdlCatalog.Member::name).toList())
                        .append("）\n");
            }
        }
        out.append('\n');
    }

    private static void appendColumnRow(
            StringBuilder out, MdlCatalog.Column column, boolean includeExpression) {
        String description = column.description() == null ? "" : column.description();
        if (includeExpression && column.expression() != null) {
            description = description + "（表达式：" + column.expression() + "）";
        }
        out.append("| ")
                .append(column.name())
                .append(" | ")
                .append(column.type())
                .append(" | ")
                .append(description)
                .append(" |\n");
    }

    /**
     * Widens a time-dimension spec whose endpoints coincide on a calendar unit. wren-core
     * compiles CubeQuery dateRange as {@code >= start AND < end} (source-verified via
     * {@code cube_query_to_sql}, wren_core 0.8.0), so ``d,d`` is always empty; the official
     * CLI/MCP docs never state this, so the gateway fixes up the common single-day,
     * single-month, single-year cases here. Splits mirror the official
     * {@code _parse_time_dimension} ({@code split(":", 2)} then comma-split, so datetime
     * endpoints survive). Non-calendar granularities, datetime endpoints and anything
     * unparseable pass through untouched — the tool description documents the exclusive-end
     * contract either way.
     */
    private static String normalizeTimeDimension(String spec) {
        String[] parts = spec.split(":", 3);
        if (parts.length < 2) {
            return spec;
        }
        String granularity = parts[1].trim().toLowerCase(Locale.ROOT);
        if (!TIME_GRANULARITIES.contains(granularity)) {
            return spec;
        }
        String normalizedPrefix = parts[0] + ":" + granularity;
        if (parts.length == 2) {
            return normalizedPrefix;
        }
        String normalized = normalizedPrefix + ":" + parts[2];
        String[] dates = parts[2].split(",");
        if (dates.length != 2) {
            return normalized;
        }
        String start = dates[0].trim();
        String end = dates[1].trim();
        if (!start.equals(end)) {
            return normalized;
        }
        LocalDate startDate;
        try {
            startDate = LocalDate.parse(start);
        } catch (DateTimeException | NullPointerException e) {
            return normalized;
        }
        LocalDate endDate =
                switch (granularity) {
                    case "day" -> startDate.plusDays(1);
                    case "week" -> startDate.plusWeeks(1);
                    case "month" -> startDate.plusMonths(1);
                    case "quarter" -> startDate.plusMonths(3);
                    case "year" -> startDate.plusYears(1);
                    default -> null;
                };
        if (endDate == null) {
            return normalized;
        }
        return normalizedPrefix + ":" + start + "," + endDate;
    }

    /**
     * Rewrites an order-by member written as the time dimension's output alias back to the raw
     * member name. wren-core validates order-by members against the raw names selected by the
     * query (measures + dimensions + time_dimensions, source-verified {@code cube.rs}, wren_core
     * 0.8.0); the compiled SQL only presents the time bucket as {@code <name>__<granularity>} in
     * the SELECT list, and the official CLI/MCP docs never warn that this alias is not orderable
     * — the model routinely echoes the alias from a previous result header (for example
     * {@code order_time__month}) and the engine then rejects the whole query. Only the exact
     * {@code <timeDimensionName>__<granularity>} shape matching this call's time dimension is
     * rewritten; raw names, other members, mismatched granularities and requests without a time
     * dimension pass through untouched.
     */
    private static List<String> normalizeOrderBy(List<String> orderBy, String timeDimension) {
        if (orderBy == null
                || orderBy.isEmpty()
                || timeDimension == null
                || timeDimension.isBlank()) {
            return orderBy;
        }
        String[] td = timeDimension.split(":", 3);
        if (td.length < 2) {
            return orderBy;
        }
        String tdName = td[0].trim();
        String granularity = td[1].trim().toLowerCase(Locale.ROOT);
        if (!TIME_GRANULARITIES.contains(granularity)) {
            return orderBy;
        }
        String alias = (tdName + "__" + granularity).toLowerCase(Locale.ROOT);
        List<String> normalized = new ArrayList<>(orderBy.size());
        boolean changed = false;
        for (String item : orderBy) {
            String rewritten = item;
            if (item != null) {
                String[] parts = item.split(":", 2);
                if (parts.length == 2 && parts[0].trim().toLowerCase(Locale.ROOT).equals(alias)) {
                    rewritten = tdName + ":" + parts[1];
                    changed = true;
                }
            }
            normalized.add(rewritten);
        }
        return changed ? normalized : orderBy;
    }

    // -----------------------------------------------------------------
    //  Scope / group resolution
    // -----------------------------------------------------------------

    /**
     * Resolves the tenant scope for a tool call; mirrors {@code
     * DataAgentToolkit#effectiveScope} — typed {@link DatasetScope} preferred, otherwise the
     * baked {@link RuntimeContext} userId, then narrowed by the conversation-level group
     * selection (the harness does not propagate typed attributes to out-of-band tool calls, but
     * sessionId survives).
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
            List<String> groups = conversationScopes.get(rc.getSessionId());
            if (groups != null && !groups.isEmpty()) {
                return new DatasetScope(base.ownerId(), groups);
            }
        }
        return base;
    }

    /**
     * Validates tenant visibility, group existence and publication state. The argument accepts
     * either the group_id (UUID) or the human-readable group name shown in
     * [DATA_SOURCES_OVERVIEW] — short names are far less error-prone for the model to reproduce
     * than 36-char ids (one-character copy typo, 2026-10-05 LLM.log). Resolution never leaves
     * the caller's own visible groups. {@code error} non-null means the caller must return that
     * string as the tool result (guided error).
     */
    private GroupRef resolveGroup(DatasetScope eff, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return new GroupRef(
                    null, "error: group_id 不能为空（取自 [DATA_SOURCES_OVERVIEW] 中知识库的逻辑模型段落）");
        }
        String raw = groupId.trim();
        List<DatasetGroupEntity> owned = groupService.listGroups(eff.ownerId());
        Set<String> allowed = eff.hasGroupFilter() ? Set.copyOf(eff.groupIds()) : null;
        List<DatasetGroupEntity> visible =
                allowed == null
                        ? owned
                        : owned.stream().filter(g -> allowed.contains(g.getId())).toList();
        DatasetGroupEntity group =
                visible.stream()
                        .filter(g -> raw.equalsIgnoreCase(g.getId()))
                        .findFirst()
                        .orElseGet(
                                () ->
                                        visible.stream()
                                                .filter(g -> raw.equalsIgnoreCase(g.getName()))
                                                .findFirst()
                                                .orElse(null));
        if (group == null) {
            return new GroupRef(null, unknownGroup(raw));
        }
        if (group.getMdlVersion() <= 0 || mdlCatalog.load(group.getId()).isEmpty()) {
            return new GroupRef(null, unavailableGroup(group));
        }
        return new GroupRef(group, null);
    }

    private static String unknownGroup(String groupId) {
        return "error: 未知或无权访问的知识库 '" + groupId + "'";
    }

    private static String unavailableGroup(DatasetGroupEntity group) {
        return switch (String.valueOf(group.getMdlState())) {
            case "INITIALIZING" -> "error: 知识库「" + group.getName() + "」的基础 MDL 正在初始化，暂不可问数";
            case "FAILED" -> "error: 知识库「" + group.getName() + "」的基础 MDL 初始化失败，请在建模页重试";
            default -> "error: 知识库「" + group.getName() + "」没有有效的已发布 MDL，请先完成基础 MDL 初始化";
        };
    }

    // -----------------------------------------------------------------
    //  Rendering
    // -----------------------------------------------------------------

    private static String groupHeader(DatasetGroupEntity group) {
        return "**知识库：** " + group.getName() + "（group_id: `" + group.getId() + "`）\n\n";
    }

    /** DIRTY groups keep serving the previous valid publish until the next successful rebuild. */
    private static String dirtyNote(DatasetGroupEntity group) {
        if (!STATE_DIRTY.equals(group.getMdlState())) {
            return "";
        }
        return "\n\n*(提示：该知识库存在尚未生效的变更，本次查询使用上一次成功发布的 MDL 快照。)*";
    }

    /**
     * Renders the wren MCP payload {@code {columns, rows:[{col:val}], row_count, truncated}} as a
     * compact markdown report; falls back to the raw JSON when the payload does not have the
     * expected shape.
     */
    private static String renderTable(String payload) {
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            return rawPayload(payload);
        }
        JsonNode cols = root.path("columns");
        JsonNode rows = root.path("rows");
        if (!cols.isArray() || !rows.isArray()) {
            return rawPayload(payload);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**查询结果：**\n\n");
        sb.append('|');
        for (JsonNode c : cols) {
            sb.append(' ').append(cell(c)).append(" |");
        }
        sb.append('\n');
        sb.append('|').append("---|".repeat(Math.max(1, cols.size()))).append('\n');
        for (JsonNode row : rows) {
            sb.append('|');
            for (JsonNode c : cols) {
                sb.append(' ').append(cell(row.path(c.asText()))).append(" |");
            }
            sb.append('\n');
        }
        if (rows.isEmpty()) {
            sb.append("*(0 rows returned)*\n");
        }
        boolean truncated = root.path("truncated").asBoolean(false);
        if (truncated) {
            sb.append("\n(结果已截断，仅返回前 ").append(rows.size()).append(" 行；请加聚合或筛选条件，或调整 limit 后重试)");
        }
        return sb.toString();
    }

    private static String rawPayload(String payload) {
        return "**查询结果（原始 JSON）：**\n\n```json\n" + payload + "\n```\n";
    }

    private static String cell(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "NULL";
        }
        String value = node.isValueNode() ? node.asText() : node.toString();
        String flat = value.replace("\r", " ").replace("\n", " ").replace("|", "\\|");
        return flat.length() <= CELL_TRUNCATION ? flat : flat.substring(0, CELL_TRUNCATION) + "…";
    }

    // -----------------------------------------------------------------
    //  CSV data handoff (specs/016, ADR 0030)
    // -----------------------------------------------------------------

    /**
     * Persists the query result as a CSV inside the session's sandbox workspace and returns the
     * reference line appended after the markdown table, so {@code run_python} reads the data with
     * {@code pd.read_csv('data/<file>')} instead of the model copying rows through the context.
     *
     * <p>Best-effort by contract: {@code null}/{@code rc}/{@code sessionId} missing, non-tabular
     * payload, write failure and oversized results all return an empty string (or the oversized
     * hint) — the markdown table remains the only result, never an error.
     *
     * <p>The file name is content-addressed (first 12 hex chars of the payload SHA-256), so a
     * re-run of the same query overwrites the same file instead of accumulating copies.
     */
    private String dataFileNote(RuntimeContext rc, String payload) {
        if (sandboxFilesystem == null || rc == null || rc.getSessionId() == null) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode cols = root.path("columns");
            JsonNode rows = root.path("rows");
            if (!cols.isArray() || !rows.isArray()) {
                return "";
            }
            byte[] csv = renderCsv(cols, rows).getBytes(StandardCharsets.UTF_8);
            if (csv.length > MAX_DATA_FILE_BYTES) {
                return "\n*(结果过大未生成数据文件，请加聚合或筛选条件，或调小 limit 后重试)*\n";
            }
            String fileName = sha256Hex(payload).substring(0, 12) + ".csv";
            String path = RUNPYTHON_DIR + sanitize(rc.getSessionId()) + "/data/" + fileName;
            List<FileUploadResponse> uploads =
                    sandboxFilesystem.uploadFiles(
                            rc, List.of(new AbstractMap.SimpleEntry<>(path, csv)));
            if (uploads.isEmpty() || !uploads.get(0).isSuccess()) {
                log.warn("[wren] CSV handoff upload failed for session {}", rc.getSessionId());
                return "";
            }
            return "\n**数据文件：** data/"
                    + fileName
                    + "（"
                    + rows.size()
                    + " 行 × "
                    + cols.size()
                    + " 列；run_python 中 pd.read_csv('data/"
                    + fileName
                    + "') 读取）\n";
        } catch (Exception e) {
            log.warn(
                    "[wren] CSV handoff degraded for session {}: {}",
                    rc.getSessionId(),
                    e.getMessage());
            return "";
        }
    }

    /** RFC 4180 CSV rendering: cells with commas/quotes/newlines are quoted, NULL becomes empty. */
    private static String renderCsv(JsonNode cols, JsonNode rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendCsvCell(sb, csvCell(cols.get(i)));
        }
        sb.append('\n');
        for (JsonNode row : rows) {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendCsvCell(sb, csvCell(row.path(cols.get(i).asText())));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Cell text mirroring {@link #cell} extraction without the markdown flattening. */
    private static String csvCell(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static void appendCsvCell(StringBuilder sb, String value) {
        if (value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            sb.append('"').append(value.replace("\"", "\"\"")).append('"');
        } else {
            sb.append(value);
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Same session-directory sanitisation as {@link RunPythonTool} so both share one root. */
    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /** Outcome of {@link #resolveGroup}: either the validated group or the guided error text. */
    private record GroupRef(DatasetGroupEntity group, String error) {}
}
