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
package io.agentscope.dataagent.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.dataagent.dataset.DatasetContextProvider;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.dataset.WrenSkillsLocator;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.runtime.marketplace.GitDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace;
import io.agentscope.dataagent.runtime.marketplace.NacosDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry.DataAgentMarketplaceFactoryRegistration;
import io.agentscope.dataagent.runtime.session.DataDynamicContextMiddleware;
import io.agentscope.dataagent.runtime.session.GroupScopedMemoryNamespace;
import io.agentscope.dataagent.tools.data.DataSourceRegistry;
import io.agentscope.dataagent.tools.data.ModelingToolkitRegistrar;
import io.agentscope.dataagent.web.middleware.DebugLoggingMiddleware;
import io.agentscope.dataagent.web.middleware.ModelingHitlMiddleware;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.IdentityLinkRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration for the agentscope-dataagent web module.
 *
 * <p>Assembles a {@link DataAgentBootstrap} from {@code .agentscope/agentscope.json} in the working
 * directory (defaults to {@code dataagent.workspace}), then registers a {@link ChatUiChannel} with
 * {@link DmScope#PER_PEER} so each authenticated user gets an isolated agent session and namespace.
 *
 * <h2>Property prefix</h2>
 *
 * <p>All config keys live under {@code dataagent.*}.
 *
 * <h2>Filesystem topology</h2>
 *
 * <p>DataAgent is a multi-tenant deployable. Every {@link
 * io.agentscope.harness.agent.HarnessAgent} runs against a per-{@code (userId, agentId)} live
 * Docker sandbox owned by {@link UserSandboxRegistry}: the {@link
 * io.agentscope.dataagent.runtime.gateway.HarnessGateway} attaches that sandbox to the
 * {@link io.agentscope.core.agent.RuntimeContext} as a {@link
 * io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox() external sandbox} per
 * call, so the harness takes its Priority-1 acquire path and the agent reads/writes through the
 * exact same container the browser workspace controllers use.
 *
 * <p>Multi-replica deployments must front the app with sticky load-balancing by {@code userId} —
 * the registry is in-memory only, and two pods would otherwise spin up independent containers
 * for the same user.
 *
 * <h2>Model wiring (priority order)</h2>
 *
 * <ol>
 *   <li>If a {@link Model} Spring Bean is already present (provided by another
 *       {@code @Configuration}), it is used as-is.
 *   <li>Otherwise, if {@code dataagent.openai.api-key} is set, an {@link OpenAIChatModel} is
 *       created automatically — point {@code dataagent.openai.base-url} at any
 *       OpenAI-compatible endpoint (DeepSeek, vLLM, one-api, DashScope compatible-mode, ...).
 *   <li>Otherwise, if {@code dataagent.dashscope.api-key} is set, a {@link DashScopeChatModel} is
 *       created automatically.
 *   <li>If none of the above is available, the app starts without a model (agent calls will fail
 *       until one is configured).
 * </ol>
 *
 * <p>Note: model wiring uses <em>method-parameter</em> injection in {@code @Bean} methods (not
 * field-level {@code @Autowired}) to avoid a circular-dependency with the {@code Model} bean
 * defined in this same class.
 *
 * <h2>Agent config</h2>
 *
 * <p>If {@code ~/.agentscope/dataagent/agentscope.json} does not exist, a minimal default agent
 * config is auto-generated so the app starts without manual setup.
 */
@Configuration
public class DataAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentConfig.class);

    /**
     * 内置系统提示词默认值。当 {@code dataagent.agent.sys-prompt} 未配置时使用。
     *
     * <p>提示词三层分工（ADR 0007）：
     * <ul>
     *   <li>常驻人格/流程 → AGENTS.md（本常量）</li>
     *   <li>常驻动作点硬门 → {@code @Tool} 描述</li>
     *   <li>按需操作手册 → SKILL.md</li>
     * </ul>
     */
    static final String DEFAULT_AGENT_SYS_PROMPT =
            "# 数据分析智能体\n\n"
                    + "你是一个数据分析智能体，帮助用户查询、分析和可视化数据。\n\n"
                    + "# 最高优先级原则\n\n"
                    + "1. 显式需求优先：只做用户明确要求的分析，不主动扩展维度。\n"
                    + "2. 每次工具调用前自检：这一步是否是完成用户请求的必要动作？\n"
                    + "3. 已有结果足够时停止工具调用，直接回答。\n"
                    + "4. 不编造数据，不确定时说明局限。\n\n"
                    + "# 工作流程\n\n"
                    + "1. 理解用户问题，确定显式要求的对象、维度、时间范围、条件。\n"
                    + "2. 查阅 system prompt 中的动态上下文：\n"
                    + "   - [DATA_SOURCES_OVERVIEW] — Wren 可查询知识库的逻辑模型/Cube 轻量目录\n"
                    + "   - [KNOWLEDGE_BASE_OVERVIEW] — 可用知识库\n"
                    + "3. 所有结构化数据查询统一走 Wren，按官方决策树选工具：聚合指标问题先核对"
                    + " [DATA_SOURCES_OVERVIEW] 的 Cube 清单，Cube 成员能覆盖时优先用 wren_query_cube（引擎确定性"
                    + "编译聚合，错误率更低）；已发布 View 能直接覆盖问题时优先用 wren_run_sql 按视图名直接查询"
                    + "（视图口径已经建模审阅）；需要跨模型属性时先用 wren_describe_model "
                    + "展开 many 侧关联字段组，只查询一个逻辑模型及其投影列，让 Wren 按 relationship condition 自动 JOIN；"
                    + "语义资产都无法表达时再用 wren_run_sql 编写其他逻辑 SQL，显式 JOIN 是最后兜底且只能引用逻辑模型名；"
                    + "复杂查询先加载 sql-analysis 技能。\n"
                    + "4. 禁止使用物理表名、datasetId 或 sourceId 猜测查询，也不存在物理表直查回退通道。\n"
                    + "5. 如果是知识/文档类问题，使用 retrieve_evidence。\n"
                    + "6. 如果需要图表，使用 render_chart。\n"
                    + "7. 结果足够时直接回答，不要为凑数继续调用工具。\n\n"
                    + "# 回答规则\n\n"
                    + "- 结论中的每一个计数都必须与自己列出的明细行数一致；不一致时以明细为准重算后再回答。\n"
                    + "- 默认用 markdown 表格呈现结构化数据。\n"
                    + "- 不主动生成图表，除非用户原话包含趋势/对比/分布等视觉分析语义。\n"
                    + "- 不主动生成 PDF/Excel/PPT 等文件，除非用户明确要求。\n"
                    + "- 所有输出必须使用简体中文：包括思考过程、工具调用说明、图表标题、轴标签、图例、代码注释等。";

    /**
     * 建模助手协议层剧本（specs/022，ADR 0035）：建模领域知识（流程、YAML 结构、Cube 模板、校验清单）
     * 的单一事实源是 wren 官方 skills（经 harness 技能仓库挂载，load_skill_through_path 按需加载），
     * 本剧本只承载平台特有协议——HITL、写工具纪律、播种约定、发布出口与语言；不再复述官方剧本内容。
     */
    public static final String MODELING_SCRIPT =
            "# 语义建模助手\n\n"
                    + "你是知识库的语义建模助手。建模流程、目录布局、YAML 字段写法与 Cube/视图模板全部以官方 wren"
                    + " 技能为准（经 load_skill_through_path 加载）；你通过 write_file / patch_file"
                    + " 编辑工程文件、用 create_view 创建命名视图承载复杂口径（唯一事实源），平台自动预检并经 HITL"
                    + " 卡片让用户确认。发布动作永远引导用户到「语义建模」页完成，对话内不做发布。\n\n"
                    + "# 硬约束（违反即失败）\n\n"
                    + "1. 只增不改：不得主动修改或删除用户已确认的关系/Cube/视图；发现冲突时停下来问用户。\n"
                    + "2. 一切变更只经 write_file / patch_file 落文件；绝不口头承诺已保存——改前先 read_file"
                    + " 核对现状，写完以 validate_mdl 或重新 read_file 为准。\n"
                    + "3. 列名只能引用 list_modeling_state 或 read_file 实际存在的列（含模型文件里的计算列）；不猜列名，不确定就先确认。\n"
                    + "4. models/ 物理模型由平台播种，只追加不重建——官方技能里的数据库探查/建模型步骤在本平台不适用。\n"
                    + "5. 复杂口径（跨表 JOIN/窗口函数/CTE/HAVING 复合过滤）一律用 create_view 创建命名视图"
                    + "（views/<name>/ 双文件，平台三道预检）；视图 SQL 只引用逻辑模型名、仅用引擎兼容函数"
                    + "（对照表见 create_view 工具描述，如「最近 N 天」用 CURRENT_DATE - INTERVAL N DAY）；"
                    + "models/ 下的 ref_sql 派生模型是人工编辑载体，不要主动创建或修改。\n"
                    + "6. 写操作会由原生 HITL 卡片暂停，用户可采用、修改内容或拒绝；被拒绝的草案按用户意见修正后重新提交。\n"
                    + "7. 过程汇报纪律（specs/021）：每次调用工具前最多用一句话说明动作；文件内容、预检报告、校验 issue"
                    + " 清单由界面任务行与确认卡展示，不要在对话里粘贴草案全文或复述工具返回的细节；完整方案说明只在最终答复给出。\n\n"
                    + "# 官方技能（建模知识唯一来源）\n\n"
                    + "- generate-mdl：建模全流程（Phase 序列、validate/build 命令与常见报错）。\n"
                    + "- enrich-context：资产落点决策与模板——写 Cube 前必须先加载它并读 references/cube_proposals；"
                    + "业务词/规则落点见 references/gap_catalog。\n"
                    + "- usage：CLI 用法与 SQL/校验报错排查。\n"
                    + "建模流程不确定先加载 generate-mdl；Cube 模板与业务词落点加载 enrich-context 并读其"
                    + " references/cube_proposals、references/gap_catalog。不要凭记忆写 YAML 结构。\n\n"
                    + "# 节奏\n\n"
                    + "1. 开场：调 list_modeling_state"
                    + " 盘点现状（表与列、已确认关系、待决策候选、Cube/视图/派生模型、解析问题），规划待办；需要精确定位文件时用"
                    + " list_files / read_file。用户怀疑候选不全时用 suggest_relations 重算。\n"
                    + "2. 关系候选：一次性提交 decide_relations 批量提案（relations_json 数组，每条含"
                    + " relation_id/action/join_type/note，note 写清业务含义如「一个用户有多条访问记录」）由"
                    + " HITL 卡片供用户多选；用户确认后平台一次写入 relationships.yml 并返回校验结论。单条补充决策才用"
                    + " decide_relation；复合键需要第二列时用 add_relation 传入完整列对。\n"
                    + "3. 每轮写入收尾调 validate_mdl，有 issue 按报错 patch_file 修复后重验；度量/过滤表达式用"
                    + " wren_cube_query（sql_only 默认开，免连库转译）验证，跨表口径可用 wren_dry_plan 展开；"
                    + "复杂口径（跨表 JOIN/窗口/CTE/HAVING）用 create_view 建命名视图。\n"
                    + "4. 收尾：validate_mdl 全绿后列出全部已写文件与对应口径，引导用户前往「语义建模」页审阅并发布。\n\n"
                    + "# 语言\n\n"
                    + "所有输出使用简体中文。";

    @Value("${dataagent.openai.api-key:}")
    private String openaiApiKey;

    @Value("${dataagent.openai.base-url:}")
    private String openaiBaseUrl;

    @Value("${dataagent.openai.model-name:gpt-4o}")
    private String openaiModelName;

    @Value("${dataagent.openai.stream:true}")
    private boolean openaiStream;

    @Value("${dataagent.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${dataagent.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    @Value("${dataagent.dashscope.stream:true}")
    private boolean dashscopeStream;

    @Value(
            "${DATAAGENT_AGENT_SYS_PROMPT:${dataagent.agent.sys-prompt:"
                    + DEFAULT_AGENT_SYS_PROMPT
                    + "}}")
    private String agentSysPrompt;

    @Value("${dataagent.agent.name:data-agent}")
    private String agentName;

    @Value("${dataagent.agent.subagents-enabled:false}")
    private boolean subagentsEnabled;

    @Value("${dataagent.workspace:}")
    private String workspaceDir;

    /**
     * Docker image for the agent-runtime sandbox filesystem spec. Mirrors {@code
     * DataAgentWorkspaceConfig#sandboxImage} so the spec-default acquire path and the
     * {@code UserSandboxRegistry} external-sandbox path (Priority-1) start the same image.
     */
    @Value("${dataagent.sandbox.image:agentscope/dataagent-sandbox:latest}")
    private String sandboxImage;

    // -----------------------------------------------------------------
    //  Model bean — OpenAI-compatible first, DashScope fallback. Only
    //  created when the matching api-key is set AND no other Model bean is
    //  already present in the context. Both are skipped when the property is
    //  blank so Optional<Model> injection sites receive Optional.empty().
    // -----------------------------------------------------------------

    /**
     * Creates an {@link OpenAIChatModel} bean when {@code dataagent.openai.api-key} is configured
     * and no other {@link Model} bean is present. Works with any OpenAI-compatible endpoint —
     * set {@code dataagent.openai.base-url} to point at compatible gateways (DeepSeek, vLLM,
     * one-api, DashScope compatible-mode, ...); a trailing {@code /v1} in the base URL is
     * handled by the client. Blank base URL falls back to the official OpenAI endpoint.
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${dataagent.openai.api-key:}' != ''")
    public Model openaiModel() {
        String baseUrl =
                openaiBaseUrl != null && !openaiBaseUrl.isBlank() ? openaiBaseUrl.trim() : null;
        log.info(
                "Building OpenAIChatModel: model={}, baseUrl={}",
                openaiModelName,
                baseUrl != null ? baseUrl : "https://api.openai.com (default)");
        return OpenAIChatModel.builder()
                .apiKey(openaiApiKey)
                .baseUrl(baseUrl)
                .modelName(openaiModelName)
                .stream(openaiStream)
                .generateOptions(
                        GenerateOptions.builder()
                                .additionalBodyParam(
                                        "thinking", java.util.Map.of("type", "disabled"))
                                .build())
                .build();
    }

    /**
     * Fallback when no OpenAI key is configured: creates a {@link DashScopeChatModel} bean when
     * {@code dataagent.dashscope.api-key} is set and no other {@link Model} bean is present
     * (including {@link #openaiModel()}). Skipped entirely when the property is blank so that
     * {@code Optional<Model>} injection sites receive {@code Optional.empty()} instead of a
     * null-valued bean.
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression(
            "'${dataagent.openai.api-key:}' == '' && '${dataagent.dashscope.api-key:}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .enableThinking(false)
                .build();
    }

    // -----------------------------------------------------------------
    //  Jackson ObjectMapper — used by MarketContributionService and other
    //  web-layer components that need JSON serialization.
    // -----------------------------------------------------------------

    /**
     * Provides a shared Jackson {@link ObjectMapper} for components that rely on constructor
     * injection. Registers any Jackson modules found on the classpath (e.g. Java Time).
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    // -----------------------------------------------------------------
    //  Core bootstrap — model injected as method parameter (no field
    //  @Autowired) to avoid circular dependency with dashscopeModel() above.
    // -----------------------------------------------------------------

    /**
     * Assembles the {@link DataAgentBootstrap}, loading agent config from {@code agentscope.json}
     * and starting the {@link ChatUiChannel} for per-user isolated sessions.
     *
     * <p>Every agent built by the bootstrap declares a {@link DockerFilesystemSpec} (per-user
     * isolation scope) sharing the same {@link SandboxClient} used by {@link UserSandboxRegistry}
     * — except sandboxless agents (currently the metadata-only modeling assistant), which are
     * built on the harness-default local filesystem over a dedicated empty workspace root and are
     * skipped by the gateway when borrowing a container, so they never depend on Docker.
     * The actual container per turn is supplied by the gateway via
     * {@link io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()} — see
     * {@link io.agentscope.dataagent.runtime.gateway.HarnessGateway#setUserSandboxRegistry}.
     *
     * @param modelOpt the {@link Model} to use, or empty if none is configured
     * @param toolEventBus the shared tool-event bus for real-time SSE streaming of tool calls
     * @param sandboxClient client used by every {@link DockerFilesystemSpec} (same instance the
     *     {@link UserSandboxRegistry} uses, so spec-defaults and registry-managed sandboxes share
     *     one Docker store)
     * @param userSandboxRegistry registry attached to the gateway after bootstrap so per-call
     *     turns receive the right per-user sandbox
     */
    @Bean
    public DataAgentBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            UserSandboxRegistry userSandboxRegistry,
            Optional<AgentStateStore> sessionOpt,
            DataSourceRegistry dataSourceRegistry,
            Optional<DatasetContextProvider> contextProviderOpt,
            MdlCatalog mdlCatalog,
            DatasetGroupRepository datasetGroupRepository,
            SessionRegistryRepository sessionRegistryRepository,
            WrenSkillsLocator wrenSkillsLocator)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig();

        DataAgentBootstrap.Builder builder =
                DataAgentBootstrap.builder()
                        .cwd(cwd)
                        .sessionStoreRepository(sessionRegistryRepository);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "No model configured. Set dataagent.openai.api-key (env"
                            + " DATAAGENT_OPENAI_API_KEY or OPENAI_API_KEY) or"
                            + " dataagent.dashscope.api-key in application.yml, or provide a Model"
                            + " bean. Agent calls will fail until a model is available.");
        }

        // AgentStateStore backend selection is independent of the workspace filesystem now that
        // workspaces
        // are sandbox-backed: each user's sandbox is reached through the in-memory
        // UserSandboxRegistry under sticky load-balancing. Operators should still provide a
        // distributed AgentStateStore bean for production so conversation state survives pod
        // restarts.
        AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
        if (sessionOpt.isEmpty()) {
            log.warn(
                    "No distributed AgentStateStore bean configured ({}); using"
                            + " InMemoryAgentStateStore. For multi-replica deployments, provide"
                            + " a DistributedStore or a distributed AgentStateStore bean"
                            + " (e.g. from agentscope-extensions-redis).",
                    AgentStateStore.class.getName());
        }

        // The modeling assistant is a metadata-only agent (relations / cubes / MDL validation)
        // and must run without the per-user Docker sandbox: it is exempt from the sandbox
        // filesystem spec below and the gateway skips borrowing a container for it, so
        // conversational modeling works even when Docker is unavailable. Its dedicated, empty
        // local workspace keeps the harness on the default local filesystem without pulling in
        // the main agent's workspace content (AGENTS.md etc.).
        Set<String> sandboxlessAgents = Set.of(ModelingToolkitRegistrar.MODELING_AGENT_ID);
        Path modelingWorkspace =
                DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT.resolveSibling(
                        "workspace-modeling-agent");
        Files.createDirectories(modelingWorkspace);

        builder.configureAllAgents(
                (agentId, b) -> {
                    if (ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) {
                        // Register before tool notifications so a paused call never looks executed.
                        b.middleware(new ModelingHitlMiddleware());
                    }
                    b.middleware(new ToolNotificationMiddleware(toolEventBus));
                    // The modeling assistant's transcript goes to its own file
                    // (logs/LLM-modeling.log) so semantic-modeling sessions stay separate from
                    // data-agent chat traffic; every other agent keeps the shared logs/LLM.log.
                    b.middleware(
                            ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)
                                    ? new DebugLoggingMiddleware("LLM_MODELING_DEBUG")
                                    : new DebugLoggingMiddleware());
                    b.stateStore(stateStore);
                    if (sandboxlessAgents.contains(agentId)) {
                        // specs/029 (ADR 0039): the modeling agent runs without a sandbox, so
                        // its long-term memory used to be scoped per user only — one shared
                        // MEMORY.md across every knowledge base, letting one group's modeling
                        // context leak into another group's conversation through the
                        // <memory_context> injection (diagnosed 2026-10-04). Route the local
                        // filesystem namespace through the conversation's DatasetScope so every
                        // single-KB modeling session gets its own "kb-<groupId>" memory
                        // namespace; multi-KB / scoped-less turns fall back to the user level.
                        b.filesystem(
                                new LocalFilesystemSpec()
                                        .namespaceFactory(GroupScopedMemoryNamespace.INSTANCE));
                    } else {
                        DockerFilesystemSpec spec =
                                new DockerFilesystemSpec().client(sandboxClient);
                        if (sandboxImage != null && !sandboxImage.isBlank()) {
                            spec.image(sandboxImage.trim());
                        }
                        // isolationScope() returns the supertype; chain it last.
                        b.filesystem(spec.isolationScope(IsolationScope.USER));
                    }

                    // DataAgent does not need filesystem tools (read_file, write_file,
                    // edit_file, list_files, grep_files, glob_files) or shell execute.
                    // run_python has its own SandboxBackedFilesystem and is unaffected.
                    b.disableFilesystemTools();
                    b.disableShellTool();

                    if (!subagentsEnabled) {
                        b.disableSubagents();
                    }

                    // Exclude run_python from eviction: even with path-based
                    // artifact references the result is small, but as a safety
                    // net we prevent eviction unconditionally so the full tool
                    // result (stdout + artifact metadata + file paths) always
                    // survives in session history for the frontend to parse.
                    Set<String> excludedTools =
                            new HashSet<>(ToolResultEvictionConfig.DEFAULT_EXCLUDED_TOOLS);
                    excludedTools.add("run_python");
                    b.toolResultEviction(
                            ToolResultEvictionConfig.builder()
                                    .excludedToolNames(excludedTools)
                                    .build());

                    // Workspace tools.json declares MCP servers (e.g. the WrenAI semantic
                    // layer) for the built-in agent. The agent filesystem is sandbox-backed and
                    // unreachable while HarnessAgent is being constructed (no sandbox exists at
                    // bootstrap time), so the loader inside HarnessAgent skips the file
                    // silently. Load it here from the local workspace root — a WorkspaceManager
                    // without a filesystem layer reads local disk — and pass it as an explicit
                    // override. Assumes the built-in agent uses the default workspace root,
                    // which is how agentscope.json auto-generation configures it.
                    ToolsConfig toolsConfig =
                            ToolsConfigLoader.load(
                                            new WorkspaceManager(
                                                    DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT))
                                    .orElse(null);
                    if (toolsConfig != null) {
                        b.toolsConfig(toolsConfig);
                    }

                    // Wren-only dynamic context injection: [DATA_SOURCES_OVERVIEW] and
                    // [KNOWLEDGE_BASE_OVERVIEW] are rebuilt from the per-call DatasetScope
                    // and appended to the system prompt on every turn. The MDL catalog exposes
                    // only a compact directory of published logical models and cubes; field-level
                    // details are retrieved on demand through wren_describe_model.
                    b.middleware(
                            new DataDynamicContextMiddleware(
                                    dataSourceRegistry,
                                    contextProviderOpt.orElse(null),
                                    mdlCatalog,
                                    datasetGroupRepository));
                });

        // Conversational modeling assistant (specs/013 M1, ADR 0024 D2): registered
        // programmatically so it becomes a global agent (AgentCatalogService.isGlobal) without
        // touching existing agentscope.json files. Tools are attached later by
        // ModelingToolkitRegistrar (runs after this bootstrap bean builds every agent).
        builder.configureAgent(
                ModelingToolkitRegistrar.MODELING_AGENT_ID,
                b -> {
                    b.name("建模助手")
                            .description(
                                    "对话式语义建模助手：梳理知识库内表关系、确认复合键关联、起草语义 Cube，" + "最终引导用户到语义建模页发布 MDL")
                            .sysPrompt(MODELING_SCRIPT)
                            .maxIters(30)
                            // Sandbox-free posture (see the sandboxlessAgents note above): the
                            // dedicated empty workspace above keeps this agent off the main
                            // agent's shared workspace content.
                            .workspace(modelingWorkspace);
                    mountWrenSkills(b, wrenSkillsLocator);
                });

        DataAgentBootstrap bootstrap = builder.build();

        // Hand the gateway a reference to the per-user sandbox registry so every run(...) turn
        // injects the user's live container as SandboxContext.externalSandbox (Priority-1 acquire
        // in SandboxManager). The gateway is constructed inside DataAgentBootstrap, which lives
        // below the web layer and cannot depend on UserSandboxRegistry directly.
        bootstrap.gateway().setUserSandboxRegistry(userSandboxRegistry);
        // Metadata-only agents never borrow a container (see setSandboxlessAgents); every other
        // agent keeps the per-user Docker sandbox injection.
        bootstrap.gateway().setSandboxlessAgents(sandboxlessAgents);

        // Build the chatui channel using the file-config's bindings & dmScope (if any),
        // so admin-edited bindings in agentscope.json are honored. Falls back to
        // PER_ACCOUNT_CHANNEL_PEER so each ChatGPT-style conversation gets its own isolated
        // session (the conversationId flows through as MsgContext.group → |g: segment).
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig configuredChatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.PER_ACCOUNT_CHANNEL_PEER)
                                .build();
        ChannelConfig chatuiCfg = conversationScopedChatUiConfig(configuredChatuiCfg);
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);
        bootstrap.start(webChannel);

        log.info(
                "DataAgentBootstrap initialized: cwd={}, chatui dmScope={}, bindings={}",
                cwd,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size());
        return bootstrap;
    }

    /**
     * Mounts the official wren skills (ADR 0035, specs/022) onto the modeling agent via the
     * harness-native skill repository, so {@code load_skill_through_path} serves the same
     * SKILL.md files the CLI channel does — the modeling playbook has a single source of truth
     * inside the installed wrenai package. Fail-soft: when the package (or its skills_content)
     * is absent the agent starts without the mount and keeps the {@code wren_skills_get} CLI
     * channel. The allowlist keeps the SaaS-oriented {@code dlt-connector} skill out of the
     * modeling surface.
     */
    private static void mountWrenSkills(
            io.agentscope.harness.agent.HarnessAgent.Builder b, WrenSkillsLocator locator) {
        var skillsRoot = locator.locate();
        if (skillsRoot.isEmpty()) {
            log.warn(
                    "Official wren skills directory not found (installed wrenai package missing or"
                            + " no SKILL.md inside); modeling-agent falls back to the"
                            + " wren_skills_get CLI channel for official guidance.");
            return;
        }
        try {
            b.skillRepository(new FileSystemSkillRepository(skillsRoot.get()));
            b.skillFilter(
                    SkillFilter.only("generate-mdl", "enrich-context", "usage", "onboarding"));
            log.info(
                    "Mounted official wren skills onto modeling-agent from {} (allowlist:"
                            + " generate-mdl, enrich-context, usage, onboarding)",
                    skillsRoot.get());
        } catch (Exception e) {
            log.warn(
                    "Failed to mount official wren skills from {}: {}; modeling-agent falls back"
                            + " to the wren_skills_get CLI channel",
                    skillsRoot.get(),
                    e.getMessage());
        }
    }

    /**
     * Registers the {@link LocalApprovalMarketplace} factory under the {@code "local"} type so
     * {@code UserMarketplaceRegistry} can hydrate per-user marketplaces backed by approved
     * contributions on disk.
     *
     * <p>The factory reads from {@code ${dataagent.shared-root}/agents/data-agent/skills} — the
     * per-agent slice for the built-in {@code data-agent}, which is the same directory the
     * per-(user, data-agent) sandbox projects in as its lower layer, so an approved skill is
     * immediately visible to every tenant of {@code data-agent} without extra wiring. Skills
     * approved for other agents live under their own {@code shared/agents/<agentId>/skills/}
     * slices and surface through those agents' own overlays; this local marketplace does not
     * cross-list them.
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration localMarketplaceFactory(
            DataAgentBootstrap bootstrap) {
        Path sharedSkills =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("skills");
        return new DataAgentMarketplaceFactoryRegistration(
                LocalApprovalMarketplace.TYPE,
                (userId, id, props, wsf) -> new LocalApprovalMarketplace(id, sharedSkills));
    }

    /**
     * Registers the {@link GitDataAgentMarketplace} factory under the {@code "git"} type. Each
     * per-user marketplace gets its own clone target under
     * {@code ${dataagent.workspace}/.cache/marketplaces/{userId}/{marketplaceId}} so distinct
     * users configuring the same upstream do not contend on a shared working copy.
     *
     * <p>Properties: {@code remoteUrl} (required), {@code branch} (optional).
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration gitMarketplaceFactory(
            DataAgentBootstrap bootstrap) {
        Path cacheRoot = bootstrap.cwd().resolve(".cache").resolve("marketplaces");
        return new DataAgentMarketplaceFactoryRegistration(
                GitDataAgentMarketplace.TYPE,
                (userId, id, props, wsf) -> {
                    String remoteUrl = stringProp(props, "remoteUrl");
                    if (remoteUrl == null || remoteUrl.isBlank()) {
                        throw new IllegalArgumentException(
                                "git marketplace '" + id + "' requires property 'remoteUrl'");
                    }
                    String branch = stringProp(props, "branch");
                    Path clone = cacheRoot.resolve(userId).resolve(id);
                    return new GitDataAgentMarketplace(id, remoteUrl, branch, clone);
                });
    }

    /**
     * Registers the {@link NacosDataAgentMarketplace} factory under the {@code "nacos"} type.
     *
     * <p>Properties: {@code serverAddr} (required), {@code namespaceId} (optional, defaults to
     * {@code "public"}), {@code username} / {@code password}, {@code accessKey} / {@code
     * secretKey}.
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration nacosMarketplaceFactory() {
        return new DataAgentMarketplaceFactoryRegistration(
                NacosDataAgentMarketplace.TYPE,
                (userId, id, props, wsf) -> {
                    String serverAddr = stringProp(props, "serverAddr");
                    if (serverAddr == null || serverAddr.isBlank()) {
                        throw new IllegalArgumentException(
                                "nacos marketplace '" + id + "' requires property 'serverAddr'");
                    }
                    return new NacosDataAgentMarketplace(
                            id,
                            serverAddr,
                            stringProp(props, "namespaceId"),
                            stringProp(props, "username"),
                            stringProp(props, "password"),
                            stringProp(props, "accessKey"),
                            stringProp(props, "secretKey"));
                });
    }

    private static String stringProp(java.util.Map<String, Object> props, String key) {
        if (props == null) return null;
        Object v = props.get(key);
        return v == null ? null : v.toString();
    }

    @Bean
    public io.agentscope.dataagent.web.identity.IdentityLinkStore identityLinkStore(
            IdentityLinkRepository repository) {
        return new io.agentscope.dataagent.web.identity.IdentityLinkStore(repository);
    }

    @Bean
    public ChatUiChannel chatUiChannel(DataAgentBootstrap bootstrap) {
        return (ChatUiChannel)
                bootstrap
                        .channelManager()
                        .getChannel(ChatUiChannel.CHANNEL_ID)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ChatUiChannel not registered in ChannelManager"));
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    static ChannelConfig conversationScopedChatUiConfig(ChannelConfig configured) {
        if (configured.dmScope() == DmScope.PER_ACCOUNT_CHANNEL_PEER) {
            return configured;
        }
        log.warn(
                "Ignoring chatui dmScope={} because browser conversation isolation requires {}",
                configured.dmScope(),
                DmScope.PER_ACCOUNT_CHANNEL_PEER);
        return ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                .defaultAgentId(configured.defaultAgentId())
                .dmScope(DmScope.PER_ACCOUNT_CHANNEL_PEER)
                .bindings(configured.bindings())
                .build();
    }

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * Auto-generates a minimal {@code ~/.agentscope/dataagent/agentscope.json} if it doesn't
     * exist, so the app can start without manual setup. The generated config defines a single
     * GLOBAL {@code data-agent} pre-wired with the {@code chatui} channel and lets the bootstrap
     * fall through to {@link DataAgentBootstrap#DEFAULT_WORKSPACE_ROOT} for the workspace
     * location.
     *
     * <p>The workspace root is the read-only shared seed (template content, default {@code
     * AGENTS.md} / {@code skills/} / {@code subagents/} / {@code knowledge/} shipped on disk).
     * {@link UserSandboxRegistry} projects it into every fresh container; user-writable files
     * live inside the container.
     */
    private void ensureAgentscopeConfig() throws IOException {
        Path configFile = DataAgentBootstrap.DEFAULT_CONFIG_PATH;
        Path workspaceRoot = DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT;

        Files.createDirectories(configFile.getParent());
        Files.createDirectories(workspaceRoot);

        // Always ensure workspace scaffolding (AGENTS.md etc.) runs — safe because
        // scaffold() uses writeIfMissing internally. This fixes the case where
        // agentscope.json exists from a prior setup but AGENTS.md was never created.
        io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder.scaffold(
                workspaceRoot, "Data Agent", agentSysPrompt);

        if (Files.exists(configFile)) {
            return;
        }

        String agentsJson =
                """
                {
                  "main": "data-agent",
                  "agents": {
                    "data-agent": {
                      "name": "Data Agent",
                      "description": "租户隔离的数据分析助手。连接内部 SQL 数据源，起草查询，校验结果并渲染图表。",
                      "maxIters": 20
                    }
                  },
                  "channels": {
                    "chatui": {
                      "defaultAgentId": "data-agent",
                      "dmScope": "PER_ACCOUNT_CHANNEL_PEER"
                    }
                  }
                }
                """;

        Files.writeString(configFile, agentsJson);
        log.info("Auto-generated DataAgent config at {}", configFile);
    }
}
