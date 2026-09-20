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

import io.agentscope.dataagent.dataset.DatasetContextProvider;
import io.agentscope.dataagent.dataset.KnowledgeGraphService;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.service.TtsService;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wires a singleton {@link DataAgentToolkit} onto the built-in main agent's toolkit at startup,
 * so the agent can call {@code prepare_data_context}, {@code query_structured_data},
 * {@code retrieve_evidence} and {@code render_chart}.
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
    private final DataSourceRegistry registry;
    private final SqlConnector sqlConnector;
    private final DatasetContextProvider contextProvider;
    private final ChartOptionRepository chartOptions;
    private final KnowledgeGraphService knowledgeGraph;
    private final ConversationScopeRegistry conversationScopes;
    private final TtsService ttsService;

    public DataToolkitRegistrar(
            DataAgentBootstrap bootstrap,
            DataSourceRegistry registry,
            SqlConnector sqlConnector,
            DatasetContextProvider contextProvider,
            ChartOptionRepository chartOptions,
            KnowledgeGraphService knowledgeGraph,
            ConversationScopeRegistry conversationScopes,
            TtsService ttsService) {
        this.bootstrap = bootstrap;
        this.registry = registry;
        this.sqlConnector = sqlConnector;
        this.contextProvider = contextProvider;
        this.chartOptions = chartOptions;
        this.knowledgeGraph = knowledgeGraph;
        this.conversationScopes = conversationScopes;
        this.ttsService = ttsService;
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
            main.getDelegate()
                    .getToolkit()
                    .registerTool(
                            new DataAgentToolkit(
                                    registry,
                                    sqlConnector,
                                    contextProvider,
                                    chartOptions,
                                    knowledgeGraph,
                                    conversationScopes));
            log.info("Registered DataAgent toolkit onto main agent '{}'", main.getName());

            // Register the Python sandbox-execution tool. See the class javadoc for why a
            // standalone proxy (instead of the agent's own filesystem instance) is sufficient.
            SandboxBackedFilesystem sandboxFs = new SandboxBackedFilesystem();
            main.getDelegate()
                    .getToolkit()
                    .registerTool(new RunPythonTool(sandboxFs));
            log.info("Registered RunPythonTool onto main agent '{}'", main.getName());

            // Register the video report generation tool. Combines TTS narration with chart
            // images from previous run_python calls to produce an MP4 video slideshow.
            main.getDelegate()
                    .getToolkit()
                    .registerTool(new VideoReportTool(ttsService, sandboxFs));
            log.info("Registered VideoReportTool onto main agent '{}'", main.getName());
        } catch (RuntimeException e) {
            log.warn("Failed to register DataAgent toolkit onto main agent: {}", e.getMessage());
        }
    }
}
