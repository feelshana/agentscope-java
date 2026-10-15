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
            """
            # 通用数据分析助手
            使用同版本官方 Wren usage 技能、MCP 工具和知识完成问数，不建立另一套 SQL 或指标路由。
            先加载官方 usage；通过 wren_list_tools 发现当前知识库的官方工具 schema，
            wren_call_tool 的 arguments 原样遵循官方 schema。get_instructions、上下文、知识、
            memory、SQL 与 Cube 的使用方式以官方指导及实际返回为准。
            查询只能使用已授权已发布工程；不得猜测物理表、切换连接或访问其他租户。
            业务规则、知识与相似问答均通过 wren_call_tool 调用官方 get_instructions、list_knowledge、recall_queries；不调用平台自定义知识检索。
            每个新问题先调用一次 wren_call_tool 的 recall_queries，使用当前问题作为 question，limit 使用官方默认的小数量；它是相似业务问答的唯一召回入口。
            不要遍历工程文件或调用平台自定义检索；业务规则、知识目录和相似问答只通过官方 get_instructions、list_knowledge、recall_queries 获取。
            answer_question 是平台可选的答案来源标注，不会传入 Wren：
            仅对准备实际用于最终答案的业务查询填写对应问题，诊断查询不要填写。
            一条 SQL 只回答子问题时标注该子问题，不让局部 SQL 冒充完整原问题。
            只有用户点赞后才保存成功的业务样例；执行成功不代表业务正确，不自动修改语义资产。
            根据用户需要使用 run_python 或 render_chart 分析 Wren 返回的数据文件。
            不编造结果，真实说明歧义、失败和截断；已有结果足够时给出完整最终答案。
            保留证据中的空值与空结果，不擅自补零；不补造未声明的单位或时间范围。
            只呈现最终结论、必要口径和可核实依据，不呈现内部草稿推理。
            用户未要求时不主动导出文件。回答及图表使用简体中文。
            语言硬约束：所有面向用户的思考摘要、工具调用前说明、工具调用后说明和最终回答必须使用简体中文；不要输出英文解释。SQL、Python、官方工具原始字段和错误原文可以保留原格式。
            Top N 或单条结果优先使用官方 query_cube 的 order_by 与 limit；没有合适 Cube 时使用 run_sql 的标准 SQL（可按官方语法使用 LIMIT/OFFSET）。不要把 View（例如 valid_orders）传给 describe_model；View 用 get_mdl/describe_schema，Cube 用 describe_cube。
            """;

    /** Platform execution boundaries only; modeling knowledge comes from official Wren skills. */
    public static final String MODELING_SCRIPT =
            """
            # 通用 Wren 工程助手

            你是使用官方 Wren 工具完成用户任务的通用 Agent。建模和语义增强先通过 load_skill_through_path
            加载官方 generate-mdl、enrich-context、usage 或 onboarding；流程、模式选择、澄清与文件结构以技能正文为准。
            不另建平台建模流程或自行复刻官方业务规则。技能挂载不可用时可通过 wren_cli 的 skills list/get 获取官方内容。

            # 建模执行节奏

            同一会话中每个官方 skill 只加载一次；后续轮次直接使用已加载内容和当前工程状态，不重复加载完整 SKILL.md。
            开始修改前确认工程目录；当前上下文已有有效目录时直接复用，目录缺失或文件新增/删除后才按需 list_files。
            已存在的文件使用 edit_file，新文件才使用 write_file；未变化且已读取的文件无需反复读取。
            同一轮方案中每个文件只能提交一次 edit_file；先合并该文件的全部描述、主键和字段修改，再提交一个完整替换，禁止对同一路径连续发起多个 edit_file。
            生成 Cube 时，业务文档中的固定过滤条件必须落在可执行语义中：优先使用已过滤视图作为 base_object，或在 measure expression 中使用条件聚合；只写 properties.description 或 knowledge/rules 不算实现过滤。若表达式涉及关联模型，先用官方 CLI 验证关联表达式；不能因为过滤规则写入 knowledge/rules 就宣称 Cube 已完成。
            Cube 的最终验收以 context build 后的 cube query --sql-only 生成 SQL 为准：固定口径指标的 SQL 必须实际包含对应过滤谓词；若 SQL 没有过滤，必须回到 View 或条件聚合修正，再次 build 和检查，不能把“编译通过”当作“业务口径正确”。
            先完成所需发现和方案，再按原生工具 schema 提交文件；write_file 使用 path/content。
            同一方案中互不依赖的多个文件可在同一轮发起多个 write_file/edit_file 调用，由原生批次授权统一确认；不要重复写入未变化文件。
            每次 YAML 修改后只执行 context validate --strict；全部修改完成后再执行一次 context build。
            新增 Cube、视图或关系后，再执行对应的 cube query --sql-only 或 dry-plan；只读 validate/build/query 不需要用户确认。
            只有新 Cube、视图、关系或与现有口径冲突的修改需要确认，低风险的说明和规则补充合并到同一次方案中。

            # 执行边界

            用户已在页面选择当前知识库；运行上下文会提供绑定的草稿工程，文件路径相对于 /project。
            只通过通用 read_file/list_files/glob_files/grep_files/write_file/edit_file 操作该工程；
            确定性检查、构建、查询和 memory 使用 wren_cli 的官方参数数组。文件写入和 memory store
            经平台原生权限确认；工具报错按实际原因修正，不能将未执行描述为成功。
            不切换租户、工程、连接或平台管理文件，不使用宿主任意 shell。模型、视图、Cube 和知识遵循官方规范。
            Wren context build 只生成草稿 MDL，不代表已发布；需要上线时引导用户使用页面发布入口。
            问题表格是可选的用户输入，不是开始建模或发布的必经步骤。
            对本轮已按官方技能成功验证的推荐问题，完成对应的 memory store --nl/--sql 写回（建模示例使用官方 source:enrich 标签），不要把问答保存留为“如需可继续”的遗漏步骤。
            保存仍须原生写授权；用户拒绝或尚未成功验证时不得声称已保存。仅写问题、规则或执行 memory index 不等于已保存问答；收尾核对 knowledge/sql 中的问题与 SQL，并说明需页面显式发布后才能用于问数召回。
            不把字段样例当作统计结果，不编造业务定义或确认状态。

            # 语言

            所有面向用户的思考摘要、工具调用前说明、工具调用后说明和最终回答使用简体中文；不要输出英文解释。官方 CLI 原始输出、SQL、Python 和文件路径可以保留原格式。最终说明实际修改、检查结果和可执行的下一步。
            """;

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
            io.agentscope.dataagent.web.session.ConversationScopeRegistry conversationScopes,
            SessionRegistryRepository sessionRegistryRepository,
            org.springframework.beans.factory.ObjectProvider<ModelingToolkitRegistrar>
                    modelingToolkitProvider,
            WrenSkillsLocator wrenSkillsLocator,
            io.agentscope.dataagent.dataset.WrenProjectAccess wrenProjectAccess,
            io.agentscope.dataagent.dataset.WrenQueryAccess wrenQueryAccess)
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
                    if ("data-agent".equals(agentId)) {
                        configureQueryPrompt(b, agentSysPrompt);
                        mountWrenSkills(b, wrenSkillsLocator);
                    }
                    if (ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) {
                        // Register before tool notifications so a paused call never looks executed.
                        b.middleware(
                                new ModelingHitlMiddleware(
                                        (ctx, tool) -> {
                                            var registrar =
                                                    modelingToolkitProvider.getIfAvailable();
                                            if (registrar == null) return "error: 建模工具尚未就绪，未执行写入";
                                            return registrar.preflight(ctx, tool);
                                        }));
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
                    // Built-in Wren agents must not inherit a global, unscoped MCP server.
                    ToolsConfig effectiveTools = scopedWrenToolsConfig(agentId, toolsConfig);
                    if (effectiveTools != null) b.toolsConfig(effectiveTools);

                    // Wren-only dynamic context injection: [DATA_SOURCES_OVERVIEW] and
                    // [KNOWLEDGE_BASE_OVERVIEW] are rebuilt from the per-call DatasetScope
                    // and appended to the system prompt on every turn. The MDL catalog exposes
                    // only a compact directory of published logical models and cubes; field-level
                    // Details are retrieved on demand through the official describe_model tool.
                    if (ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) {
                        b.middleware(
                                new io.agentscope.dataagent.runtime.session
                                        .WrenProjectContextMiddleware(wrenProjectAccess));
                    } else if ("data-agent".equals(agentId)) {
                        b.middleware(
                                new io.agentscope.dataagent.runtime.session
                                        .WrenQueryContextMiddleware(wrenQueryAccess));
                    } else {
                        b.middleware(
                                new DataDynamicContextMiddleware(
                                        dataSourceRegistry,
                                        contextProviderOpt.orElse(null),
                                        mdlCatalog,
                                        datasetGroupRepository));
                    }
                });

        // Conversational modeling assistant (specs/013 M1, ADR 0024 D2): registered
        // programmatically so it becomes a global agent (AgentCatalogService.isGlobal) without
        // touching existing agentscope.json files. Tools are attached later by
        // ModelingToolkitRegistrar (runs after this bootstrap bean builds every agent).
        builder.configureAgent(
                ModelingToolkitRegistrar.MODELING_AGENT_ID,
                b -> {
                    b.name("建模助手")
                            .description("使用官方 Wren 技能、CLI 和通用文件工具完成当前知识库工程任务")
                            .sysPrompt(MODELING_SCRIPT)
                            .disableWorkspaceContext()
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
        bootstrap.gateway().setConversationScopes(conversationScopes);

        log.info(
                "DataAgentBootstrap initialized: cwd={}, chatui dmScope={}, bindings={}",
                cwd,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size());
        return bootstrap;
    }

    static ToolsConfig scopedWrenToolsConfig(String agentId, ToolsConfig source) {
        if (!"data-agent".equals(agentId)
                && !ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) return source;
        ToolsConfig scoped = new ToolsConfig();
        if (source != null) {
            scoped.setAllow(source.getAllow());
            scoped.setDeny(source.getDeny());
        }
        scoped.setMcpServers(java.util.Map.of());
        return scoped;
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
                        + " no SKILL.md inside); install requirements-wren.txt in the configured"
                        + " CLI environment. Modeling can use wren_cli skills get; querying can use"
                        + " the official MCP get_instructions tool.");
            return;
        }
        try {
            b.skillRepository(new FileSystemSkillRepository(skillsRoot.get()));
            b.skillFilter(
                    SkillFilter.only("generate-mdl", "enrich-context", "usage", "onboarding"));
            log.info(
                    "Mounted official wren skills onto Wren agent from {} (allowlist:"
                            + " generate-mdl, enrich-context, usage, onboarding)",
                    skillsRoot.get());
        } catch (Exception e) {
            log.warn(
                    "Failed to mount official wren skills from {}: {}; restore the configured"
                            + " official CLI environment before acceptance",
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
    static void configureQueryPrompt(
            io.agentscope.harness.agent.HarnessAgent.Builder builder, String prompt) {
        builder.sysPrompt(prompt).disableWorkspaceContext();
    }

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
