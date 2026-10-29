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

import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wires the official Wren MCP adapter and presentation tools onto the built-in main agent at
 * startup. Physical metadata and direct SQL tools are intentionally absent; all structured data
 * queries use the official Wren MCP schema.
 *
 * <p>Mirrors {@code ContributionToolRegistrar}: runs after {@link DataAgentBootstrap} has built
 * every agent, fails soft on errors so a missing tool slot does not stop the application from
 * booting.
 *
 * <p>Also registers {@link RunPythonTool} so the agent can execute Python data-analysis code in
 * the per-call Docker sandbox. A standalone {@link SandboxBackedFilesystem} proxy suffices here:
 * the live sandbox for a call is bound on the invocation's {@code RuntimeContext} by {@code
 * SandboxLifecycleMiddleware#acquireForCall}, and every tool invocation receives that same
 * context, so the proxy does not need to be the agent's own filesystem instance.
 */
@Component
public class DataToolkitRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitRegistrar.class);

    private final DataAgentBootstrap bootstrap;
    private final ChartOptionRepository chartOptions;
    private final ConversationScopeRegistry conversationScopes;
    private final WrenQueryGateway wrenGateway;
    private final DatasetGroupService datasetGroupService;
    private final MdlCatalog mdlCatalog;
    private final io.agentscope.dataagent.web.artifact.ArtifactStore artifactStore;
    private final io.agentscope.dataagent.dataset.AnswerQueryMemory answerMemory;
    private final io.agentscope.dataagent.dataset.WrenQueryAccess queryAccess;
    private final io.agentscope.dataagent.dataset.WrenPublishedProject publishedProject;
    private final org.springframework.core.env.Environment environment;

    public DataToolkitRegistrar(
            DataAgentBootstrap bootstrap,
            ChartOptionRepository chartOptions,
            ConversationScopeRegistry conversationScopes,
            WrenQueryGateway wrenGateway,
            DatasetGroupService datasetGroupService,
            MdlCatalog mdlCatalog,
            io.agentscope.dataagent.web.artifact.ArtifactStore artifactStore,
            io.agentscope.dataagent.dataset.AnswerQueryMemory answerMemory,
            io.agentscope.dataagent.dataset.WrenQueryAccess queryAccess,
            io.agentscope.dataagent.dataset.WrenPublishedProject publishedProject,
            org.springframework.core.env.Environment environment) {
        this.bootstrap = bootstrap;
        this.environment = environment;
        this.chartOptions = chartOptions;
        this.conversationScopes = conversationScopes;
        this.wrenGateway = wrenGateway;
        this.datasetGroupService = datasetGroupService;
        this.mdlCatalog = mdlCatalog;
        this.artifactStore = artifactStore;
        this.answerMemory = answerMemory;
        this.queryAccess = queryAccess;
        this.publishedProject = publishedProject;
    }

    @PostConstruct
    public void registerDataToolkit() {
        HarnessAgent main = bootstrap.agents().get(bootstrap.loadedConfig().getMain());
        if (main == null) {
            main =
                    bootstrap.agents().values().stream()
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "No agents available to register data toolkit"
                                                            + " onto"));
        }
        try {
            main.getDelegate().getToolkit().registerTool(new DataAgentToolkit(chartOptions));
            log.info("Registered DataAgent toolkit onto main agent '{}'", main.getName());
            // spec097: the parameter card of an analysis turn; refused outside such a turn.
            main.getDelegate().getToolkit().registerTool(new AnalysisToolkit());

            // Wren MCP tools are registered per invocation by WrenScopedToolkitMiddleware. A
            // project-bound client must never be placed on this shared startup toolkit.
            log.info("Wren MCP tools are invocation-scoped; no shared semantic wrapper registered");

            // Register the Python sandbox-execution tool. See the class javadoc for why a
            // standalone proxy (instead of the agent's own filesystem instance) is sufficient.
            main.getDelegate()
                    .getToolkit()
                    .registerTool(new RunPythonTool(new SandboxBackedFilesystem(), artifactStore));
            log.info("Registered RunPythonTool onto main agent '{}'", main.getName());

            WebSearchTool search = webSearch(environment);
            configureQueryWebTools(main.getDelegate().getToolkit(), search);
            log.info(
                    "Query web search {} on main agent '{}'",
                    search == null ? "disabled (no DEEPSEEK_API_KEY)" : "enabled",
                    main.getName());
        } catch (RuntimeException e) {
            log.warn("Failed to register DataAgent toolkit onto main agent: {}", e.getMessage());
        }
    }

    /** The harness tools every agent receives: a foreign search service and a fetch of any URL. */
    static final java.util.List<String> HARNESS_WEB_TOOLS =
            java.util.List.of("web_search", "web_fetch");

    /**
     * spec094: the query agent searches through the DeepSeek Search API only when a key is
     * configured, and never fetches arbitrary URLs (internal addresses included).
     */
    static void configureQueryWebTools(
            io.agentscope.core.tool.Toolkit toolkit, WebSearchTool search) {
        HARNESS_WEB_TOOLS.forEach(toolkit::removeTool);
        if (search != null) toolkit.registerTool(search);
    }

    /** Null when disabled or when no key is configured. */
    static WebSearchTool webSearch(org.springframework.core.env.PropertyResolver properties) {
        if (properties == null
                || !properties.getProperty("dataagent.web-search.enabled", Boolean.class, true))
            return null;
        String key = properties.getProperty("dataagent.web-search.api-key", "");
        if (key == null || key.isBlank()) return null;
        return new WebSearchTool(
                properties.getProperty("dataagent.web-search.base-url", "https://api.deepseek.com"),
                key.strip(),
                java.time.Duration.ofSeconds(
                        properties.getProperty(
                                "dataagent.web-search.timeout-seconds", Integer.class, 20)),
                properties.getProperty("dataagent.web-search.max-results", Integer.class, 5));
    }
}
