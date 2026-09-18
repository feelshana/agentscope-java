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
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.dataagent.dataset.DatasetContextProvider;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.runtime.marketplace.GitDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace;
import io.agentscope.dataagent.runtime.marketplace.NacosDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry.DataAgentMarketplaceFactoryRegistration;
import io.agentscope.dataagent.runtime.session.DataDynamicContextMiddleware;
import io.agentscope.dataagent.tools.data.DataSourceRegistry;
import io.agentscope.dataagent.tools.data.SqlConnector;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
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
            "${dataagent.agent.sys-prompt:"
                    + "# 数据分析智能体\n\n"
                    + "你是一个数据分析智能体，帮助用户查询、分析和可视化数据。\n\n"
                    + "# 最高优先级原则\n\n"
                    + "1. 显式需求优先：只做用户明确要求的分析，不主动扩展维度。\n"
                    + "2. 每次工具调用前自检：这一步是否是完成用户请求的必要动作？\n"
                    + "3. 已有结果足够时停止工具调用，直接回答。\n"
                    + "4. 不编造数据，不确定时说明局限。\n\n"
                    + "# 工作流程\n\n"
                    + "1. 理解用户问题，确定显式要求的对象、维度、时间范围、条件。\n"
                    + "2. 查阅 system prompt 中的动态上下文：\n"
                    + "   - [DATA_SOURCES_OVERVIEW] — 可用数据源和表结构\n"
                    + "   - [KNOWLEDGE_BASE_OVERVIEW] — 可用知识库\n"
                    + "3. 如果需要结构化数据，先用 prepare_data_context 确认列细节，"
                    + "再用 query_structured_data 查询。\n"
                    + "4. 如果是知识/文档类问题，使用 retrieve_evidence。\n"
                    + "5. 如果需要图表，使用 render_chart。\n"
                    + "6. 结果足够时直接回答，不要为凑数继续调用工具。\n\n"
                    + "# 数据源规则\n\n"
                    + "- 调用 query_structured_data 前，先查看 [DATA_SOURCES_OVERVIEW] 确认表名。\n"
                    + "- 需要确认列名拼写时用 prepare_data_context。\n"
                    + "- 没有数据源时不要编造数据，提示用户补充。\n\n"
                    + "# 回答规则\n\n"
                    + "- 默认用 markdown 表格呈现结构化数据。\n"
                    + "- 不主动生成图表，除非用户原话包含趋势/对比/分布等视觉分析语义。\n"
                    + "- 不主动生成 PDF/Excel/PPT 等文件，除非用户明确要求。\n"
                    + "- 用简体中文回答。}")
    private String agentSysPrompt;

    @Value("${dataagent.agent.name:data-agent}")
    private String agentName;

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
     * isolation scope) sharing the same {@link SandboxClient} used by {@link UserSandboxRegistry}.
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
            SqlConnector sqlConnector,
            Optional<DatasetContextProvider> contextProviderOpt)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig();

        DataAgentBootstrap.Builder builder = DataAgentBootstrap.builder().cwd(cwd);

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

        builder.configureAllAgents(
                b -> {
                    b.middleware(new ToolNotificationMiddleware(toolEventBus));
                    b.stateStore(stateStore);
                    DockerFilesystemSpec spec = new DockerFilesystemSpec().client(sandboxClient);
                    if (sandboxImage != null && !sandboxImage.isBlank()) {
                        spec.image(sandboxImage.trim());
                    }
                    // isolationScope() returns the supertype; chain it last.
                    b.filesystem(spec.isolationScope(IsolationScope.USER));

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

                    // TC-style dynamic context injection: [DATA_SOURCES_OVERVIEW] and
                    // [KNOWLEDGE_BASE_OVERVIEW] are rebuilt from the per-call DatasetScope
                    // and appended to the system prompt on every turn.
                    b.middleware(
                            new DataDynamicContextMiddleware(
                                    dataSourceRegistry,
                                    sqlConnector,
                                    contextProviderOpt.orElse(null)));
                });

        DataAgentBootstrap bootstrap = builder.build();

        // Hand the gateway a reference to the per-user sandbox registry so every run(...) turn
        // injects the user's live container as SandboxContext.externalSandbox (Priority-1 acquire
        // in SandboxManager). The gateway is constructed inside DataAgentBootstrap, which lives
        // below the web layer and cannot depend on UserSandboxRegistry directly.
        bootstrap.gateway().setUserSandboxRegistry(userSandboxRegistry);

        // Build the chatui channel using the file-config's bindings & dmScope (if any),
        // so admin-edited bindings in agentscope.json are honored. Falls back to PER_PEER
        // when no chatui entry exists.
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig chatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.PER_PEER)
                                .build();
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
            DataAgentBootstrap bootstrap) {
        Path agentscopeDir = bootstrap.cwd().resolve(".agentscope");
        return new io.agentscope.dataagent.web.identity.IdentityLinkStore(agentscopeDir);
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

        if (Files.exists(configFile)) {
            return;
        }

        Files.createDirectories(configFile.getParent());
        Files.createDirectories(workspaceRoot);

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
                      "dmScope": "MAIN"
                    }
                  }
                }
                """;

        Files.writeString(configFile, agentsJson);
        log.info("Auto-generated DataAgent config at {}", configFile);

        io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder.scaffold(
                workspaceRoot, "Data Agent", agentSysPrompt);
    }
}
