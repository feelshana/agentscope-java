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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlQuestionService;
import io.agentscope.dataagent.dataset.MdlQuestionStore;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.dataset.MdlWorkspaceReader;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.dataset.WrenCli;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent-facing toolkit for YAML-first conversational modeling (specs/019, ADR 0033): the modeling
 * assistant edits the official wren workspace files directly ({@code write_file}/{@code
 * patch_file}, HITL-gated), creates named views for structurally complex semantics through {@code
 * create_view} (specs/035, HITL-gated), and drives the official {@code wren} CLI for deterministic
 * checks ({@code context validate --strict}, {@code dry-plan}, {@code dry-run}, {@code cube query
 * --sql-only}) plus on-demand official skill guides. Workspace files are the single source of
 * truth; the other structured write tools (cubes/terms/rules) are retired — their write side now
 * lives in {@code cubes/} and {@code knowledge/} files edited through the gated write tools.
 * ref_sql derived models are a manual-edit carrier (specs/035): the assistant never creates or
 * edits {@code models/<name>/ref_sql.sql} on its own.
 *
 * <p>Every write goes through a triple gate (specs/019 §4): ① YAML must parse; ② the proposal is
 * overlaid on a throwaway workspace copy ({@link MdlWorkspaceService#copyToScratch}) and the
 * official {@code context validate --strict} must pass there — identical argv to the publish
 * chain; ③ only then is the real file written. A rejected write therefore never touches the real
 * workspace. Writes and the per-group workspace lock are serialized via
 * {@link MdlWorkspaceService#withWorkspaceLock}.
 *
 * <p>Tenant isolation mirrors {@link WrenToolkit}: the typed {@link DatasetScope} (with the
 * conversation-level group narrowing fallback) must cover the group; both visibility and
 * existence failures collapse into one indistinguishable error so probing cannot reveal other
 * users' knowledge bases. Relation proposals keep the DB candidate queue (suggest/decide/confirm/
 * reject/add) until specs/019 M4 flips their write side to {@code relationships.yml}.
 *
 * <p>Registered onto the {@code modeling-agent} at startup by {@link ModelingToolkitRegistrar}.
 */
public final class ModelingToolkit {

    private static final Logger log = LoggerFactory.getLogger(ModelingToolkit.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** View name: letters/Chinese/underscore start, then letters/digits/underscore/Chinese, ≤63 chars. */
    private static final Pattern VIEW_NAME =
            Pattern.compile("^[A-Za-z_\u4e00-\u9fff][A-Za-z0-9_\u4e00-\u9fff]{0,62}$");

    private final MdlSuggestionService suggestions;
    private final MdlPublishService mdlPublish;
    private final DatasetGroupService groupService;
    private final ConversationScopeRegistry conversationScopes;
    private final MdlWorkspaceService workspace;
    private final MdlWorkspaceReader reader;
    private final WrenProperties wrenProps;
    private final WrenCli wrenCli;
    private final MdlQuestionService questions;
    private final io.agentscope.dataagent.dataset.ModelingWorkflowService workflow;

    public ModelingToolkit(
            MdlSuggestionService suggestions,
            MdlPublishService mdlPublish,
            DatasetGroupService groupService,
            ConversationScopeRegistry conversationScopes,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader,
            WrenProperties wrenProps,
            WrenCli wrenCli) {
        this(
                suggestions,
                mdlPublish,
                groupService,
                conversationScopes,
                workspace,
                reader,
                wrenProps,
                wrenCli,
                null);
    }

    public ModelingToolkit(
            MdlSuggestionService suggestions,
            MdlPublishService mdlPublish,
            DatasetGroupService groupService,
            ConversationScopeRegistry conversationScopes,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader,
            WrenProperties wrenProps,
            WrenCli wrenCli,
            MdlQuestionService questions) {
        this(
                suggestions,
                mdlPublish,
                groupService,
                conversationScopes,
                workspace,
                reader,
                wrenProps,
                wrenCli,
                questions,
                null);
    }

    public ModelingToolkit(
            MdlSuggestionService suggestions,
            MdlPublishService mdlPublish,
            DatasetGroupService groupService,
            ConversationScopeRegistry conversationScopes,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader,
            WrenProperties wrenProps,
            WrenCli wrenCli,
            MdlQuestionService questions,
            io.agentscope.dataagent.dataset.ModelingWorkflowService workflow) {
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
        this.mdlPublish = Objects.requireNonNull(mdlPublish, "mdlPublish");
        this.groupService = Objects.requireNonNull(groupService, "groupService");
        this.conversationScopes = conversationScopes;
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.wrenProps = Objects.requireNonNull(wrenProps, "wrenProps");
        this.wrenCli = Objects.requireNonNull(wrenCli, "wrenCli");
        this.questions = questions;
        this.workflow = workflow;
    }

    // -----------------------------------------------------------------
    //  list_modeling_state — snapshot rendered from the workspace files
    // -----------------------------------------------------------------

    @Tool(
            name = "list_modeling_state",
            description =
                    """
                    查看知识库的语义建模现状快照（读自 wren 工程文件）：逻辑模型（含每张表的完整列清单——\
                    列名/类型/说明/计算列表达式）、relationships.yml 已确认关系、平台关系候选队列（待决策）、\
                    cubes/ 与 views/ 已定义资产、以及工作区解析问题。每次建模会话开场必须先调用本工具，\
                    根据待办规划下一步；提议列名时只能引用快照中实际存在的列，不要向用户索要列名。\
                    """)
    public String listModelingState(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        MdlWorkspaceReader.Snapshot snap = reader.read(c.gid());
        return workflowGuidance(c)
                + renderState(c.group(), snap, c.gid())
                + renderBusinessRules(reader.readKnowledgeRules(c.gid()))
                + (questions == null
                        ? ""
                        : "\n\n## 常用问题与验收\n"
                                + new MdlQuestionStore(workspace)
                                        .list(c.gid()).stream()
                                                .map(
                                                        q ->
                                                                q.question().id()
                                                                        + "："
                                                                        + q.question().question()
                                                                        + " ["
                                                                        + q.status()
                                                                        + "]")
                                                .collect(
                                                        java.util.stream.Collectors.joining("\n")));
    }

    // -----------------------------------------------------------------
    //  File tools — the modeling write surface (write tools are HITL-gated)
    // -----------------------------------------------------------------

    @Tool(
            name = "list_modeling_questions",
            description =
                    "查看当前知识库的常用问题、业务口径、验证和人员确认状态。问题需求用 write_file/patch_file 写入"
                        + " knowledge/questions/<英文ID>.yml，字段 question、definition、sql。SQL"
                        + " 可暂留空等待澄清，但每个用户问题最终都要生成 SQL、经 Wren"
                        + " 验证并由用户确认。先登记全部问题，合并澄清共用口径，复用或构建视图/Cube。不要求凑满 3–5"
                        + " 个核心问题，并澄清指标、粒度、单位、时间归属和过滤，不能把执行成功当作业务正确。 SQL 禁止显式 LIMIT/OFFSET 和分号；TopN"
                        + " 用 CTE 聚合与排名筛选，明确并列处理。字段样例不是实际数据范围。")
    public String listModelingQuestions(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID，单库会话可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) return c.error();
        if (questions == null) return "error: 常用问题服务不可用";
        try {
            return JSON.writeValueAsString(
                    questions.list(
                            new DatasetScope(c.group().getOwnerId(), List.of(c.gid())), c.gid()));
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @Tool(
            name = "validate_modeling_question",
            description =
                    "使用 Wren 实际执行一个常用问题的逻辑"
                        + " SQL，针对当前草稿副本，不改变发布模型。执行成功仅为待业务确认；必须引导人员在常用问题页审阅真实结果与口径后确认。模型或问题修改后需重新验证，截断、失败不得确认。")
    public String validateModelingQuestion(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "question_id", description = "knowledge/questions/<ID>.yml 中的 ID")
                    String questionId,
            @ToolParam(name = "group_id", description = "知识库 ID，单库会话可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) return c.error();
        if (questions == null) return "error: 常用问题服务不可用";
        try {
            return JSON.writeValueAsString(
                    questions.validate(
                            new DatasetScope(c.group().getOwnerId(), List.of(c.gid())),
                            c.gid(),
                            questionId));
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @Tool(
            name = "list_files",
            description =
                    """
                    列出知识库 wren 工作区的全部工程文件（相对路径 + 行数）。工作区即语义事实源：\
                    models/ 物理模型、cubes/ 语义 Cube、views/ 命名视图、relationships.yml 关系、\
                    knowledge/ 术语与业务规则。修改前先 list_files 定位目标路径，再 read_file 查看现状。\
                    """)
    public String listFiles(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        List<MdlPublishService.MdlFile> files = reader.listFiles(c.gid());
        if (files.isEmpty()) {
            return "工作区为空或尚未初始化。上传数据集并在「语义建模」页发布一次后，平台会按物理结构播种 models/；"
                    + "首次 write_file 也会自动初始化官方工程骨架。";
        }
        StringBuilder sb = new StringBuilder("## 工作区文件（").append(files.size()).append(" 个）\n\n");
        for (MdlPublishService.MdlFile f : files) {
            sb.append("- ")
                    .append(f.path())
                    .append("（")
                    .append(f.content().lines().count())
                    .append(" 行）\n");
        }
        return sb.toString();
    }

    @Tool(
            name = "read_file",
            description =
                    """
                    读取工作区内一个文件的完整内容（相对路径，如 models/orders/metadata.yml、\
                    relationships.yml、knowledge/rules/general.md）。修改前必须先读取现状；\
                    返回全文不做截断。wren_project.yml 等平台文件可读不可写。\
                    """)
    public String readFile(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "path", description = "工作区相对路径，如 models/orders/metadata.yml")
                    String path,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (path == null || path.isBlank()) {
            return "error: path 不能为空";
        }
        Path ws = workspace.workspaceRoot(c.gid()).toAbsolutePath().normalize();
        Path target = ws.resolve(path).normalize();
        if (!target.startsWith(ws)) {
            return "error: 工作区路径越界：" + path;
        }
        if (!Files.isRegularFile(target)) {
            return "error: 文件不存在：" + path + "（先调用 list_files 查看现有文件）";
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "error: 读取文件失败：" + e.getMessage();
        }
    }

    @Tool(
            name = "write_file",
            description =
                    """
                    新建或整体覆写工作区内的一个工程文件（写操作，由 HITL 卡片让用户确认后生效）。\
                    路径相对工作区根，如 cubes/order_stats/metadata.yml、relationships.yml、\
                    knowledge/rules/general.md；目录不存在会自动创建。\
                    wren_project.yml、.platform/**、target/** 由平台管理，禁止写入。\
                    平台在写入前自动执行两道预检：① YAML 可解析；② 工程副本 context validate --strict。\
                    预检失败时文件不会被写入，请根据报错修正后重试。content 必须是完整文件内容；\
                    只改局部请改用 patch_file。reason 用一句话向用户说明本次变更意图，会展示在确认卡片上。\
                    """)
    public String writeFile(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "path", description = "工作区相对路径，如 cubes/order_stats/metadata.yml")
                    String path,
            @ToolParam(name = "content", description = "完整文件内容（UTF-8 文本）") String content,
            @ToolParam(name = "reason", description = "一句话向用户说明本次变更意图（展示在确认卡片上）") String reason,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (path == null || path.isBlank()) {
            return "error: path 不能为空";
        }
        if (content == null || content.isBlank()) {
            return "error: content 不能为空（整体清空请与用户确认后写入仅含必要骨架的内容）";
        }
        try {
            workspace.resolveWritable(c.gid(), path);
        } catch (DatasetException e) {
            return "error: " + e.getMessage();
        }
        String error = gatedWrite(c.gid(), path, () -> content);
        if (error != null) {
            return error;
        }
        return "已写入 "
                + path
                + "（"
                + content.lines().count()
                + " 行，预检通过）。"
                + (reason == null || reason.isBlank() ? "" : "\n变更说明：" + reason)
                + "\n\n"
                + stateFooter(c.group());
    }

    @Tool(
            name = "patch_file",
            description =
                    """
                    对工作区内既有文件做精确局部替换（写操作，由 HITL 卡片让用户确认后生效）。\
                    original 必须与文件现有内容逐字符一致且唯一（多处命中时必须把上下文扩到唯一，\
                    或置 replace_all=true 全部替换）；replacement 是替换后的新文本。\
                    与 write_file 相同的两道预检（YAML 解析 + 工程副本 context validate --strict），\
                    预检失败文件不变。适合改一行口径、追加一个成员等小改动；大段重写用 write_file。\
                    reason 用一句话向用户说明本次变更意图。\
                    """)
    public String patchFile(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "path", description = "工作区相对路径（文件必须已存在）") String path,
            @ToolParam(name = "original", description = "要替换的原文（逐字符精确匹配）") String original,
            @ToolParam(name = "replacement", description = "替换后的新文本") String replacement,
            @ToolParam(
                            name = "replace_all",
                            description = "原文多处命中时是否全部替换；默认只允许唯一命中",
                            required = false)
                    Boolean replaceAll,
            @ToolParam(name = "reason", description = "一句话向用户说明本次变更意图（展示在确认卡片上）") String reason,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (path == null || path.isBlank()) {
            return "error: path 不能为空";
        }
        if (original == null || original.isEmpty()) {
            return "error: original 不能为空";
        }
        if (replacement == null) {
            return "error: replacement 不能为空（删除内容请传入空串）";
        }
        try {
            workspace.resolveWritable(c.gid(), path);
        } catch (DatasetException e) {
            return "error: " + e.getMessage();
        }
        boolean all = Boolean.TRUE.equals(replaceAll);
        String error =
                gatedWrite(
                        c.gid(),
                        path,
                        () -> mergedContent(c.gid(), path, original, replacement, all));
        if (error != null) {
            return error;
        }
        return "已修改 "
                + path
                + "（补丁应用成功，预检通过）。"
                + (reason == null || reason.isBlank() ? "" : "\n变更说明：" + reason)
                + "\n\n"
                + stateFooter(c.group());
    }

    @Tool(
            name = "create_view",
            description =
                    """
                    新建「命名视图」——用一段 SQL 定义的命名口径，是跨表 JOIN、窗口函数、CTE、HAVING/复合过滤类\
                    复杂口径的唯一正确载体（specs/035；写操作，HITL 卡片确认后生效）。\
                    一次调用成对写入 views/<name>/metadata.yml（名称与说明）与 views/<name>/sql.yml（口径 SQL）。\
                    写入前三道预检：工程副本 context validate --strict → context build → dry-plan 试跑 \
                    SELECT * FROM "<name>"；任一失败两个文件都不落盘。\
                    硬规矩：① statement 仅允许单条 SELECT/WITH，且只引用 models/ 下的逻辑模型名（引擎自动完成\
                    物理定位），禁止写物理表名/库名；② 视图 SQL 由 wren 内置引擎规划执行，禁止 MySQL/Doris 专有函数，\
                    必须使用以下引擎兼容写法：最近 N 天 `CURRENT_DATE - INTERVAL N DAY`（禁 DATE_SUB/DATE_ADD）、\
                    当前日期 `CURRENT_DATE`（禁 CURDATE）、取年份 `DATE_FORMAT(col,'%Y')`（禁 YEAR）、\
                    月份截断 `DATE_TRUNC('month', col)`（月末再 + INTERVAL 1 MONTH - INTERVAL 1 DAY，禁 LAST_DAY）、\
                    日期差 `DATE_DIFF(unit, a, b)`（禁 DATEDIFF/TIMESTAMPDIFF）、条件判断 `CASE WHEN`（禁 IF）、\
                    拼接聚合 `STRING_AGG(col, '分隔符')`（禁 GROUP_CONCAT）、空值兜底 IFNULL/COALESCE、\
                    时间戳秒数 `EPOCH(col)`（禁 UNIX_TIMESTAMP）；③ name 不得与现有模型/Cube/视图重名。\
                    reason 用一句话说明口径业务含义，展示在确认卡片上。\
                    """)
    public String createView(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "name", description = "视图名：中英文/下划线开头，可含数字，最长 63 字符；发布后按此名查询")
                    String name,
            @ToolParam(
                            name = "statement",
                            description = "定义口径的 SQL：单条 SELECT/WITH，只引用逻辑模型名，仅用引擎兼容函数（见工具描述对照表）")
                    String statement,
            @ToolParam(name = "description", description = "口径业务含义；可选", required = false)
                    String description,
            @ToolParam(name = "reason", description = "一句话向用户说明本次变更意图（展示在确认卡片上）") String reason,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (name == null || name.isBlank()) {
            return "error: name 不能为空";
        }
        String viewName = name.trim();
        if (!VIEW_NAME.matcher(viewName).matches()) {
            return "error: name 只允许中英文/下划线/数字，须以字母、中文或下划线开头，最长 63 字符，收到：" + viewName;
        }
        String sql;
        try {
            // Same gate as the REST surface (specs/018 blocklist + specs/035 share): single
            // SELECT/WITH, comments stripped, MySQL/Doris-only functions rejected with rewrites.
            sql = MdlSuggestionService.requireViewSql(statement);
        } catch (DatasetException e) {
            return "error: statement 校验未通过（视图未创建）：" + e.getMessage();
        }
        MdlWorkspaceReader.Snapshot snap = reader.read(c.gid());
        String conflict = assetNameConflict(snap, viewName);
        if (conflict != null) {
            return "error: name 与现有" + conflict + "重名，请换一个名称";
        }
        String metaPath = "views/" + viewName + "/metadata.yml";
        String sqlPath = "views/" + viewName + "/sql.yml";
        String error =
                writeViewPair(
                        c.gid(),
                        viewName,
                        metaPath,
                        renderViewMeta(viewName, description),
                        sqlPath,
                        renderViewSql(sql));
        if (error != null) {
            return error;
        }
        markDirty(c.eff(), c.gid());
        return "已创建命名视图 "
                + viewName
                + "（"
                + metaPath
                + " + "
                + sqlPath
                + "，三道预检通过）。"
                + (reason == null || reason.isBlank() ? "" : "\n变更说明：" + reason)
                + "\n\n"
                + stateFooter(c.group());
    }

    // -----------------------------------------------------------------
    //  Official wren command package — deterministic checks, zero re-implementation
    // -----------------------------------------------------------------

    @Tool(
            name = "wren_skills_list",
            description =
                    "列出 wren 官方技能剧本清单（generate-mdl=建模全流程、enrich-context=Cube/视图模板与业务语义增强、"
                            + "usage=CLI 用法与报错排查、onboarding=工程初始化）。建模流程或字段写法不确定时先查清单，再用"
                            + " wren_skills_get 取对应剧本照做。")
    public String wrenSkillsList(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        return runWorkspaceCommand(c.gid(), List.of("skills", "list"), "skills list");
    }

    @Tool(
            name = "wren_skills_get",
            description =
                    """
                    获取一个 wren 官方技能剧本全文（先 wren_skills_list 查名字）。\
                    要连同 references/ 参考文档（如 enrich-context 的 cube_proposals、gap_catalog 模板）一起拿时必须设 full=true——\
                    这是取模板与细则的唯一途径；script 参数仅用于技能自带可执行脚本（scripts/ 目录），不能用于 references 文档。\
                    写 Cube/YAML 前先取模板，不要凭记忆写结构。\
                    """)
    public String wrenSkillsGet(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "name", description = "技能名（取自 wren_skills_list）") String name,
            @ToolParam(name = "full", description = "是否附带参考文档", required = false) Boolean full,
            @ToolParam(name = "script", description = "技能内置脚本名；可选", required = false) String script,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (name == null || name.isBlank()) {
            return "error: name 不能为空";
        }
        List<String> args = new ArrayList<>(List.of("skills", "get", name.trim()));
        if (Boolean.TRUE.equals(full)) {
            args.add("--full");
        }
        if (script != null && !script.isBlank()) {
            args.add("--script");
            args.add(script.trim());
        }
        return runWorkspaceCommand(c.gid(), args, "skills get " + name.trim());
    }

    @Tool(
            name = "wren_context_show",
            description =
                    """
                    以官方 CLI 视角查看工程上下文（context show）。format：summary（默认，人类可读）、\
                    yaml、json。适合在文件改动后交叉核对官方解析结果，或向用户展示工程全貌。\
                    """)
    public String wrenContextShow(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "format",
                            description = "输出格式：summary / yaml / json；默认 summary",
                            required = false)
                    String format,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        String f =
                format == null || format.isBlank()
                        ? "summary"
                        : format.trim().toLowerCase(Locale.ROOT);
        if (!List.of("summary", "yaml", "json").contains(f)) {
            return "error: format 仅支持 summary / yaml / json，收到：" + format;
        }
        return runWorkspaceCommand(
                c.gid(), List.of("context", "show", "-o", f), "context show -o " + f);
    }

    @Tool(
            name = "wren_context_instructions",
            description =
                    """
                    输出工程 knowledge/rules 下的业务规则（context instructions），供问数与 enrich 消费。\
                    注意：这不是工程编辑指引——目录布局与字段写法请用 wren_skills_get 获取官方技能。\
                    """)
    public String wrenContextInstructions(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        return runWorkspaceCommand(
                c.gid(), List.of("context", "instructions"), "context instructions");
    }

    @Tool(
            name = "wren_dry_plan",
            description =
                    """
                    把一条 SQL 经当前工程 MDL 展开（dry-plan，免数据库）：输出物化后的可执行 SQL，\
                    用于验证模型引用是否成立。工具会先自动 context build 生成 target/mdl.json。\
                    需要先建 target 产物的场景请优先用 validate_mdl / wren_context_show。\
                    """)
    public String wrenDryPlan(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "sql", description = "要展开的 SQL（经 MDL 物化，不连库）") String sql,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (sql == null || sql.isBlank()) {
            return "error: sql 不能为空";
        }
        String built = ensureMdlBuilt(c.gid());
        if (built != null) {
            return built;
        }
        return runWorkspaceCommand(
                c.gid(),
                List.of("dry-plan", "--sql", sql.trim(), "-d", wrenProps.dataSource()),
                "dry-plan");
    }

    @Tool(
            name = "wren_dry_run",
            description =
                    """
                    对数据源做解析+校验级试跑（dry-run，不返回结果行）。需要数据库连接 profile；\
                    工具会先自动 context build。适合发布前对关键 SQL 做最后一道确认。\
                    """)
    public String wrenDryRun(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "sql", description = "要试跑校验的 SQL") String sql,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (sql == null || sql.isBlank()) {
            return "error: sql 不能为空";
        }
        String built = ensureMdlBuilt(c.gid());
        if (built != null) {
            return built;
        }
        return runWorkspaceCommand(c.gid(), List.of("dry-run", "--sql", sql.trim()), "dry-run");
    }

    @Tool(
            name = "wren_cube_list",
            description = "列出工程中全部语义 Cube（cube list，读自 context build 产物 mdl.json）。工具会先自动 build。")
    public String wrenCubeList(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        String built = ensureMdlBuilt(c.gid());
        if (built != null) {
            return built;
        }
        return runWorkspaceCommand(c.gid(), List.of("cube", "list"), "cube list");
    }

    @Tool(
            name = "wren_cube_describe",
            description =
                    """
                    查看单个 Cube 的官方结构化描述（cube describe，JSON 输出）。工具会先自动 build。\
                    提议修改 Cube 前先用它核对现状。\
                    """)
    public String wrenCubeDescribe(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "name", description = "Cube 名（取自 wren_cube_list）") String name,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (name == null || name.isBlank()) {
            return "error: name 不能为空";
        }
        String built = ensureMdlBuilt(c.gid());
        if (built != null) {
            return built;
        }
        return runWorkspaceCommand(
                c.gid(), List.of("cube", "describe", name.trim()), "cube describe " + name.trim());
    }

    @Tool(
            name = "wren_cube_query",
            description =
                    """
                    按语义成员查询 Cube（cube query）。sql_only=true（默认）只做 wren-core 转译、\
                    免连库，返回将执行的 SQL——校验度量/维度/过滤表达式是否成立的首选；\
                    sql_only=false 时真实执行（需连接 profile）。filters 每个形如 dim:op[:value]，\
                    操作符含 eq/ne/gt/ge/lt/le/is_null/is_not_null/in/not_in；order_by 形如\
                    member:asc|desc；time_dimension 形如 name:granularity[:start,end]。\
                    工具会先自动 build。\
                    """)
    public String wrenCubeQuery(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "cube", description = "Cube 名") String cube,
            @ToolParam(name = "measures", description = "度量成员名，逗号分隔") String measures,
            @ToolParam(name = "dimensions", description = "维度成员名，逗号分隔；可选", required = false)
                    String dimensions,
            @ToolParam(
                            name = "time_dimension",
                            description = "时间维度 spec：name:granularity[:start,end]；可选",
                            required = false)
                    String timeDimension,
            @ToolParam(
                            name = "filters",
                            description = "过滤 spec 列表：dim:op[:value]；可选",
                            required = false)
                    List<String> filters,
            @ToolParam(
                            name = "order_by",
                            description = "排序 spec 列表：member:asc|desc；可选",
                            required = false)
                    List<String> orderBy,
            @ToolParam(name = "limit", description = "返回行数上限；可选", required = false) Integer limit,
            @ToolParam(name = "offset", description = "偏移行数；可选", required = false) Integer offset,
            @ToolParam(name = "sql_only", description = "只转译不执行（免连库）；默认 true", required = false)
                    Boolean sqlOnly,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (cube == null || cube.isBlank()) {
            return "error: cube 不能为空";
        }
        if (measures == null || measures.isBlank()) {
            return "error: measures 不能为空（至少一个度量成员）";
        }
        String built = ensureMdlBuilt(c.gid());
        if (built != null) {
            return built;
        }
        List<String> args = new ArrayList<>(List.of("cube", "query", "--cube", cube.trim()));
        args.add("--measures");
        args.add(measures.trim());
        if (dimensions != null && !dimensions.isBlank()) {
            args.add("--dimensions");
            args.add(dimensions.trim());
        }
        if (timeDimension != null && !timeDimension.isBlank()) {
            args.add("--time-dimension");
            args.add(timeDimension.trim());
        }
        if (filters != null) {
            for (String f : filters) {
                if (f == null || f.isBlank()) {
                    continue;
                }
                args.add("--filter");
                args.add(f.trim());
            }
        }
        if (orderBy != null) {
            for (String o : orderBy) {
                if (o == null || o.isBlank()) {
                    continue;
                }
                args.add("--order-by");
                args.add(o.trim());
            }
        }
        if (limit != null) {
            args.add("--limit");
            args.add(String.valueOf(limit));
        }
        if (offset != null) {
            args.add("--offset");
            args.add(String.valueOf(offset));
        }
        if (!Boolean.FALSE.equals(sqlOnly)) {
            args.add("--sql-only");
        }
        return runWorkspaceCommand(c.gid(), args, "cube query --cube " + cube.trim());
    }

    // -----------------------------------------------------------------
    //  validate_mdl — publish-chain preview (unchanged argv contract)
    // -----------------------------------------------------------------

    @Tool(
            name = "validate_mdl",
            description =
                    """
                    校验当前工作区能否通过发布链（严格校验 + 构建 + 视图试跑 + Cube 转译），返回 ok/issues。\
                    建模整体收尾时必须调用：通过只代表工程结构可用，按返回的工作流下一步验证并确认业务问题，满足条件后到「发布」页\
                    （发布动作只能在页面上完成，对话内不做发布）。\
                    """)
    public String validateMdl(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        try {
            MdlPublishService.MdlValidation result = mdlPublish.validate(c.gid());
            StringBuilder sb = new StringBuilder();
            sb.append("## MDL 校验结果：").append(result.ok() ? "通过" : "未通过").append("\n\n");
            if (result.issues().isEmpty()) {
                sb.append("工程校验通过，仅证明模型结构可用，不代表业务结果已确认。\n");
            } else {
                sb.append("| 级别 | 来源 | 问题 |\n|---|---|---|\n");
                for (MdlPublishService.MdlIssue i : result.issues()) {
                    sb.append("| ")
                            .append(i.severity())
                            .append(" | ")
                            .append(i.source())
                            .append(" | ")
                            .append(i.message().replace("|", "\\|").replace("\n", " "))
                            .append(" |\n");
                }
            }
            return sb.toString() + workflowGuidance(c);
        } catch (DatasetException ex) {
            return "error: " + ex.getMessage();
        }
    }

    // -----------------------------------------------------------------
    //  Write gate (specs/019 §4) — shared by write_file / patch_file
    // -----------------------------------------------------------------

    /**
     * Applies {@code contentSupplier} to {@code relative} through the triple gate, holding the
     * group's workspace lock for the whole read-merge-validate-write cycle. Gate ① YAML parse
     * (yaml/yml only); gate ② overlay on a scratch copy and run the official {@code context
     * validate --strict} — identical argv to the publish chain, so a proposal that passes here
     * will not surprise the publish; gate ③ the real write. Returns {@code null} on success or
     * the error text (the real workspace is never touched on any failure path).
     */
    private String gatedWrite(String gid, String relative, Supplier<String> contentSupplier) {
        return workspace.withWorkspaceLock(
                gid,
                () -> {
                    String newContent;
                    try {
                        newContent = contentSupplier.get();
                    } catch (IllegalArgumentException | DatasetException e) {
                        return "error: " + e.getMessage();
                    }
                    String error = validateProposal(gid, relative, newContent);
                    if (error != null) {
                        return error;
                    }
                    try {
                        Path real = workspace.resolveWritable(gid, relative);
                        Files.createDirectories(real.getParent());
                        Files.writeString(real, newContent, StandardCharsets.UTF_8);
                    } catch (IOException | DatasetException e) {
                        return "error: 写入工作区失败：" + e.getMessage();
                    }
                    return null;
                });
    }

    /**
     * Gates ①+②+③ of the write pipeline shared by the real write and the HITL preview: the YAML
     * must parse, the proposal must pass the official {@code context validate --strict} on a
     * throwaway workspace copy (identical argv to the publish chain), and the copy must survive
     * a full-engine {@code context build} + {@code dry-plan "SELECT 1"} — specs/023. The dry-plan
     * gate exists because the official validate does not deserialize cube/relation members
     * (measured: a type-less cube passes validate with exit 0), so serde-only errors such as
     * {@code missing field 'type'} only surface under the planner. Returns {@code null} when the
     * proposal is acceptable, the error text otherwise; the real workspace is never touched.
     */
    private String validateProposal(String gid, String relative, String newContent) {
        if (relative.replace('\\', '/').startsWith("knowledge/questions/")
                && !relative.replace('\\', '/')
                        .matches("knowledge/questions/[A-Za-z0-9_-]{1,64}\\.yml")) {
            return "error: 问题文件须直接保存为 knowledge/questions/<英文ID>.yml";
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            try {
                YAML.readTree(newContent);
            } catch (Exception e) {
                return "error: YAML 解析失败，文件未写入（" + relative + "）：" + firstLine(e.getMessage());
            }
        }
        Path scratch;
        try {
            workspace.ensureWorkspace(gid);
            scratch = workspace.copyToScratch(gid);
        } catch (DatasetException e) {
            return "error: " + e.getMessage();
        }
        try {
            Path overlay = scratch.resolve(relative).normalize();
            Files.createDirectories(overlay.getParent());
            Files.writeString(overlay, newContent, StandardCharsets.UTF_8);
            if (relative.replace('\\', '/').startsWith("knowledge/questions/")) {
                MdlQuestionStore questionStore = new MdlQuestionStore(workspace);
                questionStore.readQuestions(scratch);
                questionStore.requireModelingPlan(
                        scratch, overlay.getFileName().toString().replaceFirst("\\.yml$", ""));
            }
            WrenCli.Result r =
                    wrenCli.run(
                            scratch,
                            wrenProps.timeout(),
                            List.of("context", "validate", "--strict"));
            if (!r.ok()) {
                return "error: 工程校验未通过，文件未写入（context validate --strict 对工程副本执行）：\n"
                        + r.output()
                        + "\n提示：--strict 模式下 warning 也判失败；若报出的 warning 来自本文件的同类问题"
                        + "（如多条关系缺 join_type），请在本次变更中一并修完，或用 write_file 重写全文件，"
                        + "不要逐条提交。";
            }
            WrenCli.Result b =
                    wrenCli.run(scratch, wrenProps.timeout(), List.of("context", "build"));
            if (!b.ok()) {
                return "error: 工程校验未通过，文件未写入（context build 对工程副本执行）：\n" + b.output();
            }
            WrenCli.Result d =
                    wrenCli.run(
                            scratch,
                            wrenProps.timeout(),
                            List.of("dry-plan", "--sql", "SELECT 1", "-d", wrenProps.dataSource()));
            if (!d.ok()) {
                return "error: 工程校验未通过，文件未写入（dry-plan 物化预检 SELECT 1 对工程副本执行）：\n"
                        + d.output()
                        + "\n提示：这类 serde 报错通常表示某个 Cube/关系成员缺必填字段（如 measure/"
                        + "dimension 缺 type、关系缺 join_type），请用 load_skill_through_path 加载官方 "
                        + "enrich-context 技能并对照 references/cube_proposals 模板修复后再写。";
            }
        } catch (DatasetException e) {
            return "error: 常用问题预检失败，文件未写入：" + e.getMessage();
        } catch (IOException e) {
            return "error: 准备工程预检副本失败，文件未写入：" + e.getMessage();
        } finally {
            workspace.deleteScratchQuietly(gid);
        }
        return null;
    }

    /**
     * Triple gate for the named-view pair (specs/035): both files are overlaid on a scratch copy,
     * then the official {@code context validate --strict}, {@code context build} and a per-view
     * {@code dry-plan "SELECT * FROM \"<name>\""} must pass — the same argv family the publish
     * chain uses. The D1 experiment (target/wren-refsql-verify) proved dry-plan rejects views with
     * functions the engine cannot bind, without a database connection, so a broken dialect never
     * lands. Any failure leaves the real workspace untouched; the pair is committed under the
     * group workspace lock.
     */
    private String writeViewPair(
            String gid,
            String viewName,
            String metaPath,
            String metaContent,
            String sqlPath,
            String sqlContent) {
        return workspace.withWorkspaceLock(
                gid,
                () -> {
                    Path scratch;
                    try {
                        workspace.ensureWorkspace(gid);
                        workspace.resolveWritable(gid, metaPath);
                        workspace.resolveWritable(gid, sqlPath);
                        scratch = workspace.copyToScratch(gid);
                    } catch (DatasetException e) {
                        return "error: " + e.getMessage();
                    }
                    try {
                        Path metaOverlay = scratch.resolve(metaPath).normalize();
                        Path sqlOverlay = scratch.resolve(sqlPath).normalize();
                        Files.createDirectories(metaOverlay.getParent());
                        Files.writeString(metaOverlay, metaContent, StandardCharsets.UTF_8);
                        Files.writeString(sqlOverlay, sqlContent, StandardCharsets.UTF_8);
                        WrenCli.Result v =
                                wrenCli.run(
                                        scratch,
                                        wrenProps.timeout(),
                                        List.of("context", "validate", "--strict"));
                        if (!v.ok()) {
                            return "error: 工程校验未通过，视图未创建（context validate --strict 对工程副本执行）：\n"
                                    + v.output();
                        }
                        WrenCli.Result b =
                                wrenCli.run(
                                        scratch, wrenProps.timeout(), List.of("context", "build"));
                        if (!b.ok()) {
                            return "error: 工程校验未通过，视图未创建（context build 对工程副本执行）：\n" + b.output();
                        }
                        WrenCli.Result d =
                                wrenCli.run(
                                        scratch,
                                        wrenProps.timeout(),
                                        List.of(
                                                "dry-plan",
                                                "--sql",
                                                "SELECT * FROM \"" + viewName + "\"",
                                                "-d",
                                                wrenProps.dataSource()));
                        if (!d.ok()) {
                            return "error: 视图试跑失败，未创建（dry-plan SELECT * FROM \""
                                    + viewName
                                    + "\" 对工程副本执行）：\n"
                                    + d.output()
                                    + "\n提示：请检查 statement 是否引用了不存在的逻辑模型或列（只允许引用"
                                    + " models/ 下的模型名），以及是否使用了引擎不支持的函数（按 create_view"
                                    + " 描述中的兼容写法对照改写，如 CURRENT_DATE - INTERVAL N DAY）。";
                        }
                        Path realMeta = workspace.resolveWritable(gid, metaPath);
                        Path realSql = workspace.resolveWritable(gid, sqlPath);
                        Files.createDirectories(realMeta.getParent());
                        Files.writeString(realMeta, metaContent, StandardCharsets.UTF_8);
                        Files.writeString(realSql, sqlContent, StandardCharsets.UTF_8);
                    } catch (IOException | DatasetException e) {
                        return "error: 写入工作区失败：" + e.getMessage();
                    } finally {
                        workspace.deleteScratchQuietly(gid);
                    }
                    return null;
                });
    }

    /**
     * Renders the {@code metadata.yml} of a named view (official {@code views/<name>} layout):
     * the name plus optional {@code properties.description}; the sibling {@code sql.yml} carries
     * the statement.
     */
    private static String renderViewMeta(String viewName, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(MdlPublishService.yamlScalar(viewName)).append('\n');
        if (description != null && !description.isBlank()) {
            sb.append("properties:\n");
            sb.append("  description: ")
                    .append(MdlPublishService.yamlScalar(description))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * Renders the {@code sql.yml} of a named view: a {@code statement} block scalar whose body
     * keeps the statement's own line breaks, indented two spaces — the exact shape the official
     * loader reads back (verified in target/wren-refsql-verify).
     */
    private static String renderViewSql(String statement) {
        StringBuilder sb = new StringBuilder("statement: |-\n");
        for (String line : statement.strip().split("\n", -1)) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    /** Detects name clashes against existing models/cubes/views via normalized identifier comparison. */
    private static String assetNameConflict(MdlWorkspaceReader.Snapshot snap, String name) {
        String target = MdlPublishService.sanitizeIdentifier(name, 63);
        for (MdlWorkspaceReader.WorkspaceModel m : snap.models()) {
            if (MdlPublishService.sanitizeIdentifier(m.name(), 63).equals(target)) {
                return "模型 `" + m.name() + "`";
            }
        }
        for (MdlWorkspaceReader.WorkspaceCube cube : snap.cubes()) {
            if (MdlPublishService.sanitizeIdentifier(cube.name(), 63).equals(target)) {
                return "Cube `" + cube.name() + "`";
            }
        }
        for (MdlWorkspaceReader.WorkspaceView v : snap.views()) {
            if (MdlPublishService.sanitizeIdentifier(v.name(), 63).equals(target)) {
                return "视图 `" + v.name() + "`";
            }
        }
        return null;
    }

    /** Applies a {@code patch_file} replacement with the exact-match / uniqueness rules. */
    private String mergedContent(
            String gid, String relative, String original, String replacement, boolean all) {
        Path ws = workspace.workspaceRoot(gid).toAbsolutePath().normalize();
        Path target = ws.resolve(relative).normalize();
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在：" + relative + "（新建文件请用 write_file）");
        }
        String current;
        try {
            current = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("读取文件失败：" + e.getMessage(), e);
        }
        int first = current.indexOf(original);
        if (first < 0) {
            throw new IllegalArgumentException(
                    "original 在文件中不存在（需逐字符精确匹配）：" + preview(original) + "；请先 read_file 核对现状");
        }
        int second = current.indexOf(original, first + 1);
        if (second >= 0 && !all) {
            throw new IllegalArgumentException("original 在文件中命中多处，请扩大上下文使其唯一，或置 replace_all=true");
        }
        return all
                ? current.replace(original, replacement)
                : new StringBuilder(current)
                        .replace(first, first + original.length(), replacement)
                        .toString();
    }

    /**
     * HITL preview for the file-change card (specs/019 §5): resolves the merged before/after
     * content and runs gates ①+② under the group workspace lock without writing, so the card can
     * render the diff and the validation verdict before the user confirms. {@code toolName} must
     * be {@code write_file} or {@code patch_file} (enforced by the HTTP caller); {@code input} is
     * the raw tool input map, so an edited draft can be previewed with the same code path that
     * will execute it.
     */
    public PreviewResult previewChange(String gid, String toolName, Map<String, Object> input) {
        return workspace.withWorkspaceLock(gid, () -> previewLocked(gid, toolName, input));
    }

    private PreviewResult previewLocked(String gid, String toolName, Map<String, Object> input) {
        String path = stringValue(input.get("path"));
        if (path == null || path.isBlank()) {
            return new PreviewResult(false, "path 不能为空", "", "");
        }
        Path target;
        try {
            target = workspace.resolveWritable(gid, path);
        } catch (DatasetException e) {
            return new PreviewResult(false, e.getMessage(), "", "");
        }
        String oldContent = "";
        if (Files.isRegularFile(target)) {
            try {
                oldContent = Files.readString(target, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return new PreviewResult(false, "读取当前文件失败：" + e.getMessage(), "", "");
            }
        }
        String newContent;
        if ("write_file".equals(toolName)) {
            String content = stringValue(input.get("content"));
            if (content == null || content.isBlank()) {
                return new PreviewResult(false, "content 不能为空", oldContent, "");
            }
            newContent = content;
        } else {
            String original = stringValue(input.get("original"));
            if (original == null || original.isEmpty()) {
                return new PreviewResult(false, "original 不能为空", oldContent, "");
            }
            Object replacementRaw = input.get("replacement");
            String replacement = replacementRaw == null ? "" : String.valueOf(replacementRaw);
            boolean all = Boolean.TRUE.equals(input.get("replace_all"));
            try {
                newContent = mergedContent(gid, path, original, replacement, all);
            } catch (IllegalArgumentException | DatasetException e) {
                return new PreviewResult(false, e.getMessage(), oldContent, "");
            }
        }
        String error = validateProposal(gid, path, newContent);
        return new PreviewResult(error == null, error, oldContent, newContent);
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    /** Before/after content plus the gate ①+② verdict for one HITL file-change preview. */
    public record PreviewResult(boolean ok, String error, String oldContent, String newContent) {}

    /** Runs one official command inside the group's workspace, passing CLI output through. */
    private String runWorkspaceCommand(String gid, List<String> args, String label) {
        Path ws = workspace.workspaceRoot(gid);
        if (!Files.isRegularFile(ws.resolve("wren_project.yml"))) {
            return "error: 工作区尚未初始化（先在「语义建模」页发布一次完成播种，或用 write_file 建立工程文件后再试）";
        }
        WrenCli.Result r = wrenCli.run(ws, wrenProps.timeout(), args);
        return r.ok() ? r.output() : "error: wren " + label + " 失败：\n" + r.output();
    }

    /**
     * Runs {@code context build} inside the workspace so command-package reads/translations
     * ({@code dry-plan}, {@code dry-run}, {@code cube *}) always see a fresh {@code
     * target/mdl.json}; the target is CLI output, not an agent write. Returns {@code null} on
     * success or the error text.
     */
    private String ensureMdlBuilt(String gid) {
        Path ws = workspace.workspaceRoot(gid);
        if (!Files.isRegularFile(ws.resolve("wren_project.yml"))) {
            return "error: 工作区尚未初始化（先在「语义建模」页发布一次完成播种，或用 write_file 建立工程文件后再试）";
        }
        WrenCli.Result r = wrenCli.run(ws, wrenProps.timeout(), List.of("context", "build"));
        return r.ok() ? null : "error: context build 失败（后续命令需要 target/mdl.json）：\n" + r.output();
    }

    private static String firstLine(String output) {
        if (output == null) {
            return "(无详情)";
        }
        String t = output.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    private static String preview(String s) {
        String t = s.replace("\n", "\\n").replace("\r", "");
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    // -----------------------------------------------------------------
    //  Relation candidates — DB queue retained; M4 flips the write side to
    //  relationships.yml (specs/019 §4). Confirmed edges are rendered from the
    //  file; this queue only carries PENDING proposals for HITL decisions.
    // -----------------------------------------------------------------

    @Tool(
            name = "suggest_relations",
            description =
                    """
                    重新计算知识库的表关系候选：列名规则 + LLM 推断 + 连接类型（基数）探测，\
                    合并为 PENDING 候选后返回完整建模现状。适合开场时待办为空、或用户怀疑候选不全时调用。\
                    注意：人工已确认（已写入 relationships.yml）与已否决的边不会被重算覆盖。
                    """)
    public String suggestRelations(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        try {
            suggestions.refreshRelations(c.gid());
        } catch (DatasetException e) {
            return "error: " + e.getMessage();
        }
        MdlWorkspaceReader.Snapshot snap = reader.read(c.gid());
        return "## 关系候选（重算完成）\n\n"
                + renderState(c.group(), snap, c.gid())
                + renderBusinessRules(reader.readKnowledgeRules(c.gid()));
    }

    @Tool(
            name = "confirm_relation",
            description =
                    """
                    确认一条关系候选（仅当用户明确同意该关系时才可调用）。可选调整连接类型（\
                    MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE）或交换方向（swap=true 表示用户认为\
                    方向反了）。确认会写入工程关系文件（发布后生效于问数链路）。
                    """)
    public String confirmRelation(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "relation_id", description = "关系 ID（取自 list_modeling_state 的候选队列）")
                    String relationId,
            @ToolParam(
                            name = "join_type",
                            description = "连接类型：MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE；可选",
                            required = false)
                    String joinType,
            @ToolParam(name = "swap", description = "是否交换源/目标方向（用户认为方向反了时为 true）", required = false)
                    Boolean swap,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        try {
            DatasetRelationEntity e =
                    suggestions.confirmRelation(
                            c.gid(),
                            relationId,
                            normalizeJoinType(joinType),
                            Boolean.TRUE.equals(swap));
            markDirty(c.eff(), c.gid());
            return "已确认关系 " + edgeLabel(e) + "。\n\n" + stateFooter(c.group());
        } catch (DatasetException | IllegalArgumentException ex) {
            return "error: " + ex.getMessage();
        }
    }

    @Tool(
            name = "reject_relation",
            description =
                    """
                    否决一条关系候选（仅当用户明确表示该关系不成立时才可调用）。被否决的关系在后续\
                    重算中不会再回来。
                    """)
    public String rejectRelation(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "relation_id", description = "关系 ID（取自 list_modeling_state 的候选队列）")
                    String relationId,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        try {
            DatasetRelationEntity e = suggestions.rejectRelation(c.gid(), relationId);
            markDirty(c.eff(), c.gid());
            return "已否决关系 " + edgeLabel(e) + "。\n\n" + stateFooter(c.group());
        } catch (DatasetException ex) {
            return "error: " + ex.getMessage();
        }
    }

    @Tool(
            name = "decide_relation",
            description =
                    """
                    提交一条关系的结构化决策提案。多条候选时优先用 decide_relations 一次批量提交，\
                    本工具仅用于对单条候选做补充决策。调用后不会立即执行，而会由 HITL 卡片让用户选择：\
                    CONFIRM（采用推荐）、ADJUST（调整方向/基数/对齐字段后采用）、REJECT（不建立）或\
                    SKIP（暂不处理）。默认提交 action=CONFIRM；前端可在保持同一工具 ID 的情况下修改\
                    action、join_type、swap、source_columns、target_columns 后恢复。不要先输出 A/B/C 文本等待用户手输。
                    """)
    public String decideRelation(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "relation_id", description = "关系 ID（取自 list_modeling_state 的候选队列）")
                    String relationId,
            @ToolParam(
                            name = "action",
                            description = "CONFIRM / ADJUST / REJECT / SKIP；提案默认 CONFIRM")
                    String action,
            @ToolParam(
                            name = "join_type",
                            description = "采用后的连接类型：MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE；可选",
                            required = false)
                    String joinType,
            @ToolParam(name = "swap", description = "是否交换源/目标方向；可选", required = false) Boolean swap,
            @ToolParam(
                            name = "source_columns",
                            description = "调整后的源侧对齐字段列表；与 target_columns 同时提供",
                            required = false)
                    List<String> sourceColumns,
            @ToolParam(
                            name = "target_columns",
                            description = "调整后的目标侧对齐字段列表；与 source_columns 一一对应",
                            required = false)
                    List<String> targetColumns,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        String decision = action == null ? "CONFIRM" : action.trim().toUpperCase(Locale.ROOT);
        try {
            if ("SKIP".equals(decision)) {
                return "已暂时跳过关系（" + relationId + "），未修改建模状态。";
            }
            DatasetRelationEntity relation;
            if ("REJECT".equals(decision)) {
                relation = suggestions.rejectRelation(c.gid(), relationId);
                markDirty(c.eff(), c.gid());
                return "已否决关系 " + edgeLabel(relation) + "。\n\n" + stateFooter(c.group());
            }
            if (!"CONFIRM".equals(decision) && !"ADJUST".equals(decision)) {
                return "error: action 必须是 CONFIRM / ADJUST / REJECT / SKIP";
            }
            relation =
                    suggestions.confirmRelation(
                            c.gid(),
                            relationId,
                            normalizeJoinType(joinType),
                            Boolean.TRUE.equals(swap),
                            sourceColumns,
                            targetColumns);
            markDirty(c.eff(), c.gid());
            return ("ADJUST".equals(decision) ? "已调整并确认关系 " : "已确认关系 ")
                    + edgeLabel(relation)
                    + "。\n\n"
                    + stateFooter(c.group());
        } catch (DatasetException | IllegalArgumentException ex) {
            return "error: " + ex.getMessage();
        }
    }

    @Tool(
            name = "decide_relations",
            description =
                    """
                    批量提交多条关系候选的结构化决策提案（specs/024）：一次调用即可让用户在一张\
                    确认卡上多选，代替逐条 decide_relation。relations_json 是 JSON 数组字符串，每项字段：\
                    relation_id（必填）、action（CONFIRM/ADJUST/REJECT/SKIP，默认 CONFIRM）、\
                    join_type（MANY_TO_ONE/ONE_TO_MANY/ONE_TO_ONE）、swap（交换方向）、\
                    source_columns/target_columns（复合键对齐字段，成对提供）。\
                    示例：[{"relation_id":"<id1>","action":"CONFIRM","join_type":"MANY_TO_ONE"}]。\
                    任一条目非法将整批拒绝；确认即一次性写入 relationships.yml 并附 context validate \
                    --strict 校验结论，之后不得复述或再次征求确认（specs/036）。
                    """)
    public String decideRelations(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(
                            name = "relations_json",
                            description =
                                    "JSON 数组字符串，每项含 relation_id 与 action，可选 join_type/swap/"
                                            + "source_columns/target_columns")
                    String relationsJson,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        List<RelationDecision> batch;
        try {
            batch =
                    JSON.readValue(
                            relationsJson == null ? "" : relationsJson,
                            new TypeReference<List<RelationDecision>>() {});
        } catch (Exception e) {
            return "error: relations_json 不是合法的 JSON 数组：" + e.getMessage();
        }
        if (batch.isEmpty()) {
            return "error: relations_json 不能为空数组";
        }
        Map<String, DatasetRelationEntity> known = new LinkedHashMap<>();
        for (DatasetRelationEntity r : suggestions.listRelations(c.gid())) {
            known.put(r.getId(), r);
        }
        for (int i = 0; i < batch.size(); i++) {
            String problem = validateDecision(batch.get(i), i, known);
            if (problem != null) {
                return "error: " + problem + "（整批拒绝，未做任何修改）";
            }
        }
        StringBuilder done = new StringBuilder();
        int confirmed = 0;
        int rejected = 0;
        int skipped = 0;
        try {
            for (RelationDecision d : batch) {
                String act =
                        d.action() == null ? "CONFIRM" : d.action().trim().toUpperCase(Locale.ROOT);
                if ("SKIP".equals(act)) {
                    skipped++;
                    continue;
                }
                if ("REJECT".equals(act)) {
                    suggestions.rejectRelation(c.gid(), d.relationId());
                    rejected++;
                    done.append("- 已否决 ").append(d.relationId()).append('\n');
                    continue;
                }
                DatasetRelationEntity e =
                        suggestions.confirmRelation(
                                c.gid(),
                                d.relationId(),
                                normalizeJoinType(d.joinType()),
                                Boolean.TRUE.equals(d.swap()),
                                d.sourceColumns(),
                                d.targetColumns());
                confirmed++;
                done.append("- ")
                        .append("ADJUST".equals(act) ? "已调整并确认 " : "已确认 ")
                        .append(edgeLabel(e));
                String note =
                        d.note() == null || d.note().isBlank() ? e.getDescription() : d.note();
                if (note != null && !note.isBlank()) {
                    done.append("：").append(truncate(note, 80));
                }
                done.append('\n');
            }
        } catch (DatasetException | IllegalArgumentException ex) {
            return "error: 第 "
                    + (confirmed + rejected + skipped + 1)
                    + " 条执行失败（之前的条目已生效）："
                    + ex.getMessage();
        }
        if (confirmed + rejected > 0) {
            markDirty(c.eff(), c.gid());
        }
        StringBuilder out = new StringBuilder("## 批量决策完成\n\n").append(done);
        out.append("\n共确认 ")
                .append(confirmed)
                .append(" 条，否决 ")
                .append(rejected)
                .append(" 条，跳过 ")
                .append(skipped)
                .append(" 条。\n");
        if (confirmed + rejected > 0) {
            out.append("\n### 工程校验（context validate --strict）\n\n");
            try {
                WrenCli.Result v =
                        wrenCli.run(
                                workspace.workspaceRoot(c.gid()),
                                wrenProps.timeout(),
                                List.of("context", "validate", "--strict"));
                out.append(
                        v.ok() ? "通过。\n" + v.output() : "未通过（决策已生效，请引导修复工程后再发布）：\n" + v.output());
            } catch (RuntimeException e) {
                out.append("校验命令执行失败：").append(e.getMessage());
            }
        }
        out.append('\n').append(stateFooter(c.group()));
        return out.toString();
    }

    /** specs/024: null when the entry is valid, otherwise the reason the whole batch is rejected. */
    private static String validateDecision(
            RelationDecision d, int index, Map<String, DatasetRelationEntity> known) {
        if (d == null || d.relationId() == null || d.relationId().isBlank()) {
            return "第 " + (index + 1) + " 条缺少 relation_id";
        }
        if (!known.containsKey(d.relationId())) {
            return "第 " + (index + 1) + " 条的 relation_id `" + d.relationId() + "` 不在本知识库候选队列中";
        }
        String act = d.action() == null ? "CONFIRM" : d.action().trim().toUpperCase(Locale.ROOT);
        if (!"CONFIRM".equals(act)
                && !"ADJUST".equals(act)
                && !"REJECT".equals(act)
                && !"SKIP".equals(act)) {
            return "第 "
                    + (index + 1)
                    + " 条 action 必须是 CONFIRM / ADJUST / REJECT / SKIP，收到："
                    + d.action();
        }
        try {
            normalizeJoinType(d.joinType());
        } catch (IllegalArgumentException e) {
            return "第 " + (index + 1) + " 条 " + e.getMessage();
        }
        boolean hasSrc = d.sourceColumns() != null && !d.sourceColumns().isEmpty();
        boolean hasTgt = d.targetColumns() != null && !d.targetColumns().isEmpty();
        if (hasSrc != hasTgt) {
            return "第 " + (index + 1) + " 条 source_columns / target_columns 必须同时提供";
        }
        if (hasSrc && d.sourceColumns().size() != d.targetColumns().size()) {
            return "第 "
                    + (index + 1)
                    + " 条复合键列数两侧必须相等（"
                    + d.sourceColumns().size()
                    + " vs "
                    + d.targetColumns().size()
                    + "）";
        }
        return null;
    }

    @Tool(
            name = "add_relation",
            description =
                    """
                    新增一条人工关系（用户口述或规则都推不出来的关系）。支持复合键：\
                    source_columns / target_columns 按顺序一一配对，如 \
                    source_columns=["province","stat_date"]、target_columns=["省","日期"]。\
                    两列数必须相等；与既有边同一对数据集时会合并列对（复合键增量生长）。\
                    确认会写入工程关系文件（发布后生效于问数链路）。
                    """)
    public String addRelation(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "source_dataset_id", description = "源数据集 ID（取自 list_modeling_state）")
                    String sourceDatasetId,
            @ToolParam(
                            name = "source_columns",
                            description = "源侧关联列，按配对顺序，如 [\"province\",\"stat_date\"]")
                    List<String> sourceColumns,
            @ToolParam(name = "target_dataset_id", description = "目标数据集 ID（取自 list_modeling_state）")
                    String targetDatasetId,
            @ToolParam(name = "target_columns", description = "目标侧关联列，与 source_columns 一一配对")
                    List<String> targetColumns,
            @ToolParam(
                            name = "join_type",
                            description = "连接类型：MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE；可选",
                            required = false)
                    String joinType,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        if (sourceColumns == null
                || sourceColumns.isEmpty()
                || targetColumns == null
                || targetColumns.isEmpty()) {
            return "error: source_columns / target_columns 至少需要一列";
        }
        if (sourceColumns.size() != targetColumns.size()) {
            return "error: 复合键列数必须两侧相等（"
                    + sourceColumns.size()
                    + " vs "
                    + targetColumns.size()
                    + "）";
        }
        try {
            DatasetRelationEntity e =
                    suggestions.addManualRelation(
                            c.gid(),
                            new MdlSuggestionService.ManualRelationPayload(
                                    sourceDatasetId,
                                    sourceColumns.get(0),
                                    targetDatasetId,
                                    targetColumns.get(0),
                                    normalizeJoinType(joinType),
                                    sourceColumns,
                                    targetColumns));
            markDirty(c.eff(), c.gid());
            String kind = sourceColumns.size() > 1 ? "复合键关系" : "关系";
            return "已新增" + kind + " " + edgeLabel(e) + "。\n\n" + stateFooter(c.group());
        } catch (DatasetException | IllegalArgumentException ex) {
            return "error: " + ex.getMessage();
        }
    }

    // -----------------------------------------------------------------
    //  Read-only dictionaries (write sides moved to knowledge/ files)
    // -----------------------------------------------------------------

    @Tool(
            name = "list_terms",
            description =
                    "列出当前知识库的业务术语。提议新术语前先调用查重；也可用于开场盘点时向用户复述已有口径。新术语请写入工程 knowledge/"
                            + " 文件（write_file）。")
    public String listTerms(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        List<SemanticTermEntity> terms = suggestions.listTerms(c.gid());
        if (terms.isEmpty()) {
            return "（暂无业务术语）";
        }
        StringBuilder sb = new StringBuilder("## 业务术语\n\n");
        for (SemanticTermEntity t : terms) {
            sb.append("- `").append(t.getId()).append("` ").append(t.getTerm());
            if (t.getSynonyms() != null && !t.getSynonyms().isBlank()) {
                sb.append("（同义：").append(t.getSynonyms()).append("）");
            }
            sb.append("：").append(t.getExplanation());
            sb.append('\n');
        }
        return sb.toString();
    }

    @Tool(
            name = "list_business_rules",
            description = "列出当前知识库已经保存的业务规则；新增规则前必须调用以避免重复或冲突。新规则请写入工程 knowledge/ 文件（write_file）。")
    public String listBusinessRules(
            DatasetScope scope,
            RuntimeContext rc,
            @ToolParam(name = "group_id", description = "知识库 ID；会话仅绑定一个知识库时可省略", required = false)
                    String groupId) {
        Ctx c = resolve(scope, rc, groupId);
        if (c.error() != null) {
            return c.error();
        }
        return renderBusinessRules(reader.readKnowledgeRules(c.gid()));
    }

    // -----------------------------------------------------------------
    //  Rendering — workspace snapshot (+ DB fallback before first seeding)
    // -----------------------------------------------------------------

    private String renderState(
            DatasetGroupEntity group, MdlWorkspaceReader.Snapshot snap, String gid) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 知识库建模现状：")
                .append(group.getName())
                .append("（group_id: `")
                .append(group.getId())
                .append("`, MDL 状态: ")
                .append(group.getMdlState() == null ? "NONE" : group.getMdlState())
                .append(", 版本: ")
                .append(group.getMdlVersion())
                .append("）\n\n");

        sb.append("### 逻辑模型（工作区 models/）\n\n");
        if (snap.models().isEmpty()) {
            sb.append("（工作区尚未播种模型文件——在「语义建模」页发布一次后平台会按数据集物理结构播种；" + "下方为数据集原始字段清单，可用于规划。）\n\n");
            appendDbDatasets(sb, group);
        } else {
            for (MdlWorkspaceReader.WorkspaceModel m : snap.models()) {
                sb.append("- `").append(m.name()).append("`");
                if (m.refSql() != null) {
                    sb.append("（ref_sql 派生模型——人工编辑载体，口径 SQL 已建模审阅，可直接按模型名查询）");
                }
                if (m.datasetId() != null) {
                    sb.append("（数据集 `")
                            .append(m.datasetId())
                            .append("「")
                            .append(m.datasetName())
                            .append("」）");
                }
                if (!m.tableName().isEmpty()) {
                    sb.append(" → ")
                            .append(m.schemaName().isEmpty() ? "" : m.schemaName() + ".")
                            .append(m.tableName());
                }
                if (m.description() != null && !m.description().isBlank()) {
                    sb.append("：").append(truncate(m.description(), 80));
                }
                if (m.refSql() != null) {
                    sb.append("｜SQL: ").append(truncate(m.refSql().replace("\n", " "), 120));
                }
                sb.append('\n');
                for (MdlWorkspaceReader.WorkspaceColumn col : m.columns()) {
                    sb.append("  - ").append(col.name());
                    if (!col.type().isEmpty()) {
                        sb.append("（").append(col.type()).append("）");
                    }
                    if (col.calculated()) {
                        sb.append("〔计算列 = ").append(col.expression()).append("〕");
                    }
                    if (col.description() != null && !col.description().isBlank()) {
                        sb.append("：").append(truncate(col.description(), 80));
                    }
                    sb.append('\n');
                }
            }
        }

        sb.append("\n### 关系（relationships.yml）\n\n");
        if (snap.relations().isEmpty()) {
            sb.append("（暂无已确认关系。可调 suggest_relations 计算候选，或经 decide_relation 卡片确认。）\n");
        }
        for (MdlWorkspaceReader.WorkspaceRelation r : snap.relations()) {
            sb.append("- ").append(r.condition());
            if (!r.joinType().isEmpty()) {
                sb.append("（").append(r.joinType()).append("）");
            }
            sb.append('\n');
        }

        sb.append("\n### 关系候选（平台队列，待决策）\n\n");
        List<DatasetRelationEntity> pending = new ArrayList<>();
        for (DatasetRelationEntity r : suggestions.listRelations(gid)) {
            if ("PENDING".equalsIgnoreCase(r.getStatus())) {
                pending.add(r);
            }
        }
        if (pending.isEmpty()) {
            sb.append("（暂无待决策候选）\n");
        } else {
            Map<String, String> names = new LinkedHashMap<>();
            for (DatasetEntity d : groupService.listDatasets(group.getOwnerId(), group.getId())) {
                names.put(d.getId(), d.getName());
            }
            for (DatasetRelationEntity r : pending) {
                sb.append("- `")
                        .append(r.getId())
                        .append("` ")
                        .append(names.getOrDefault(r.getSourceDatasetId(), r.getSourceDatasetId()))
                        .append('.')
                        .append(String.join("+", r.sourceColumnList()))
                        .append(" → ")
                        .append(names.getOrDefault(r.getTargetDatasetId(), r.getTargetDatasetId()))
                        .append('.')
                        .append(String.join("+", r.targetColumnList()))
                        .append("｜")
                        .append(r.getJoinType() == null ? "基数未探测" : r.getJoinType())
                        .append("｜来源 ")
                        .append(r.getOrigin())
                        .append('\n');
            }
            sb.append("（把以上候选一次性整理成清单——每条含 表.字段 → 表.字段与连接基数——")
                    .append("用 decide_relations 批量提交，由用户在确认卡上多选；不要逐条调用 decide_relation。）\n");
        }

        sb.append("\n### Cube（工作区 cubes/）\n\n");
        if (snap.cubes().isEmpty()) {
            sb.append(
                    "（暂无 Cube；用 write_file 写 cubes/<name>/metadata.yml 创建，写法见"
                            + " wren_context_instructions）\n");
        }
        for (MdlWorkspaceReader.WorkspaceCube cube : snap.cubes()) {
            sb.append("- ")
                    .append(cube.name())
                    .append("（base: ")
                    .append(cube.baseModel())
                    .append("）");
            if (cube.description() != null && !cube.description().isBlank()) {
                sb.append("：").append(truncate(cube.description(), 80));
            }
            sb.append('\n');
            appendMembers(sb, "measures", cube.measures());
            appendMembers(sb, "dimensions", cube.dimensions());
            appendMembers(sb, "time_dimensions", cube.timeDimensions());
        }

        sb.append("\n### 视图（工作区 views/，发布后可按视图名查询）\n\n");
        if (snap.views().isEmpty()) {
            sb.append(
                    "（暂无视图；跨表 JOIN/窗口/CTE/HAVING 类复杂口径用 create_view 创建命名视图——"
                            + "statement 只引用逻辑模型名并仅用引擎兼容函数，见工具描述对照表）\n");
        }
        for (MdlWorkspaceReader.WorkspaceView v : snap.views()) {
            sb.append("- ").append(v.name());
            if (v.description() != null && !v.description().isBlank()) {
                sb.append("：").append(truncate(v.description(), 80));
            }
            if (!v.sql().isEmpty()) {
                sb.append("｜SQL: ").append(truncate(v.sql().replace("\n", " "), 100));
            }
            sb.append('\n');
        }

        if (!snap.issues().isEmpty()) {
            sb.append("\n### 工作区解析问题（需修复后再发布）\n\n");
            for (MdlPublishService.MdlIssue i : snap.issues()) {
                sb.append("- [").append(i.severity()).append("] ").append(i.message()).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendMembers(
            StringBuilder sb, String kind, List<MdlWorkspaceReader.WorkspaceMember> members) {
        if (members.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceMember m : members) {
            parts.add(m.name() + "=" + m.expression());
        }
        sb.append("  ").append(kind).append(": ").append(String.join(", ", parts)).append('\n');
    }

    /** Physical column inventory straight from the dataset store (pre-seeding fallback). */
    private void appendDbDatasets(StringBuilder sb, DatasetGroupEntity group) {
        List<DatasetEntity> datasets = groupService.listDatasets(group.getOwnerId(), group.getId());
        if (datasets.isEmpty()) {
            sb.append("（无数据集，请先在知识库页上传或关联数据表）\n");
            return;
        }
        for (DatasetEntity d : datasets) {
            sb.append("- `").append(d.getId()).append("` ").append(d.getName()).append('\n');
            String schema = d.getColumnSchemaJson();
            if (schema == null || schema.isBlank()) {
                continue;
            }
            try {
                List<ColumnSchema> cols =
                        JSON.readValue(schema, new TypeReference<List<ColumnSchema>>() {});
                for (ColumnSchema col : cols) {
                    sb.append("  - ").append(col.name());
                    if (col.sqlType() != null && !col.sqlType().isBlank()) {
                        sb.append("（").append(col.sqlType()).append("）");
                    }
                    if (col.description() != null && !col.description().isBlank()) {
                        sb.append("：").append(truncate(col.description(), 80));
                    }
                    sb.append('\n');
                }
            } catch (Exception e) {
                log.warn("ModelingToolkit: unreadable column schema on dataset {}", d.getId());
                sb.append("  - （列结构暂不可读：请提示用户在数据集详情页检查该表字段）\n");
            }
        }
    }

    /** Business rules verbatim from the workspace's knowledge/rules/*.md (specs/019 §7). */
    private static String renderBusinessRules(String fileText) {
        if (fileText == null || fileText.isBlank()) {
            return "\n### 业务规则\n\n（暂无业务规则；用 write_file 写 knowledge/rules/<name>.md 沉淀口径）\n";
        }
        return "\n### 业务规则（工作区 knowledge/rules/）\n\n" + fileText.stripTrailing() + "\n";
    }

    // -----------------------------------------------------------------
    //  Scope / group resolution
    // -----------------------------------------------------------------

    /** Outcome of tenant resolution: the validated context or the guided error text. */
    private record Ctx(DatasetScope eff, String gid, DatasetGroupEntity group, String error) {}

    private Ctx resolve(DatasetScope scope, RuntimeContext rc, String groupId) {
        DatasetScope eff = effectiveScope(scope, rc);
        if (eff == null) {
            return new Ctx(null, null, null, "error: 无法确定租户上下文");
        }
        String gid;
        try {
            gid = resolveGroupId(eff, groupId);
        } catch (IllegalArgumentException e) {
            return new Ctx(eff, null, null, "error: " + e.getMessage());
        }
        GroupRef ref = loadGroup(eff, gid);
        if (ref.error() != null) {
            return new Ctx(eff, gid, null, ref.error());
        }
        return new Ctx(eff, gid, ref.group(), null);
    }

    /**
     * Resolves the tenant scope; mirrors {@code WrenToolkit#effectiveScope} — typed {@link
     * DatasetScope} preferred, otherwise the baked {@link RuntimeContext} userId, then narrowed
     * by the conversation-level group selection.
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
     * Resolves the effective group id: explicit argument wins; blank falls back to the
     * conversation's single selected group (the frontend pins {@code groupIds:[groupId]}), and
     * zero/many selections demand an explicit {@code group_id}.
     */
    private static String resolveGroupId(DatasetScope eff, String groupId) {
        if (groupId != null && !groupId.isBlank()) {
            return groupId.trim();
        }
        if (eff.hasGroupFilter()) {
            List<String> groups = eff.groupIds();
            if (groups.size() == 1) {
                return groups.get(0);
            }
            if (groups.isEmpty()) {
                throw new IllegalArgumentException("无法确定知识库：会话未绑定知识库，请显式传入 group_id");
            }
            throw new IllegalArgumentException(
                    "会话绑定了多个知识库，请显式传入 group_id（可选值：" + String.join(", ", groups) + "）");
        }
        throw new IllegalArgumentException("无法确定知识库：请显式传入 group_id");
    }

    /** Tenant-checked group load; visibility and existence collapse into one error (anti-probe). */
    private GroupRef loadGroup(DatasetScope eff, String groupId) {
        if (eff.hasGroupFilter() && !eff.groupIds().contains(groupId)) {
            return new GroupRef(null, unknownGroup(groupId));
        }
        DatasetGroupEntity group;
        try {
            group = groupService.getGroup(eff.ownerId(), groupId);
        } catch (DatasetException e) {
            return new GroupRef(null, unknownGroup(groupId));
        }
        return new GroupRef(group, null);
    }

    private String workflowGuidance(Ctx c) {
        if (workflow == null) return "下一步：在「验证与确认」审阅问题；全部问题有效确认后再到「发布」页。\n\n";
        try {
            var state = workflow.snapshot(c.eff(), c.gid());
            return "## 建模阶段与下一步\n" + JSON.writeValueAsString(state) + "\n\n";
        } catch (Exception e) {
            return "工作流状态暂不可用，请刷新后核对；不能据此认定可以发布。\n\n";
        }
    }

    private void markDirty(DatasetScope eff, String groupId) {
        try {
            groupService.markMdlDirty(eff.ownerId(), groupId);
        } catch (RuntimeException e) {
            // Dirty-marking is a notification side effect; the write itself already succeeded.
        }
    }

    private static String unknownGroup(String groupId) {
        return "error: 未知或无权访问的知识库 '" + groupId + "'";
    }

    private static String normalizeJoinType(String joinType) {
        if (joinType == null || joinType.isBlank()) {
            return null;
        }
        String t = joinType.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "MANY_TO_ONE", "ONE_TO_MANY", "ONE_TO_ONE" -> t;
            default ->
                    throw new IllegalArgumentException(
                            "join_type 仅支持 MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE，收到：" + joinType);
        };
    }

    private static String edgeLabel(DatasetRelationEntity e) {
        List<String> src = e.sourceColumnList();
        List<String> tgt = e.targetColumnList();
        return "`" + e.getId() + "`（" + String.join("+", src) + " → " + String.join("+", tgt) + "）";
    }

    private static String stateFooter(DatasetGroupEntity group) {
        String state = group.getMdlState() == null ? "NONE" : group.getMdlState();
        if ("PUBLISHED".equals(state) || "DIRTY".equals(state)) {
            return "*(草稿与已发布版本分开；请读取 list_modeling_state 的下一步。工程校验通过后仍需验证并由人员确认全部分析问题，最后到「发布」页。)*";
        }
        return "*(请读取 list_modeling_state 的下一步，先完成数据准备与口径建模，再进入「验证与确认」；不得跳过业务确认直接发布。)*";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** One batched relation decision (specs/024); field names follow the tool's snake_case JSON. */
    private record RelationDecision(
            @JsonProperty("relation_id") String relationId,
            @JsonProperty("action") String action,
            @JsonProperty("join_type") String joinType,
            @JsonProperty("swap") Boolean swap,
            @JsonProperty("source_columns") List<String> sourceColumns,
            @JsonProperty("target_columns") List<String> targetColumns,
            @JsonProperty("note") String note) {}

    /** Outcome of {@link #loadGroup}: either the validated group or the guided error text. */
    private record GroupRef(DatasetGroupEntity group, String error) {}
}
