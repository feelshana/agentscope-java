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
 * Wires the knowledge, chart and Wren-only structured-query tools onto the built-in main agent at
 * startup. Physical metadata and direct SQL tools are intentionally absent; all structured data
 * queries use {@link WrenToolkit}.
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
    private final DatasetContextProvider contextProvider;
    private final ChartOptionRepository chartOptions;
    private final ConversationScopeRegistry conversationScopes;
    private final WrenQueryGateway wrenGateway;
    private final DatasetGroupService datasetGroupService;
    private final MdlCatalog mdlCatalog;
    private final io.agentscope.dataagent.web.artifact.ArtifactStore artifactStore;
    private final io.agentscope.dataagent.dataset.AnswerQueryMemory answerMemory;

    public DataToolkitRegistrar(
            DataAgentBootstrap bootstrap,
            DatasetContextProvider contextProvider,
            ChartOptionRepository chartOptions,
            ConversationScopeRegistry conversationScopes,
            WrenQueryGateway wrenGateway,
            DatasetGroupService datasetGroupService,
            MdlCatalog mdlCatalog,
            io.agentscope.dataagent.web.artifact.ArtifactStore artifactStore,
            io.agentscope.dataagent.dataset.AnswerQueryMemory answerMemory) {
        this.bootstrap = bootstrap;
        this.contextProvider = contextProvider;
        this.chartOptions = chartOptions;
        this.conversationScopes = conversationScopes;
        this.wrenGateway = wrenGateway;
        this.datasetGroupService = datasetGroupService;
        this.mdlCatalog = mdlCatalog;
        this.artifactStore = artifactStore;
        this.answerMemory = answerMemory;
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
                                    contextProvider, chartOptions, conversationScopes));
            log.info("Registered DataAgent toolkit onto main agent '{}'", main.getName());

            // Wren-only structured query channel (specs/015); the sandbox proxy enables the
            // CSV data handoff to run_python (specs/016, ADR 0030).
            main.getDelegate()
                    .getToolkit()
                    .registerTool(
                            new WrenToolkit(
                                            wrenGateway,
                                            datasetGroupService,
                                            conversationScopes,
                                            mdlCatalog,
                                            new SandboxBackedFilesystem(),
                                            artifactStore)
                                    .withAnswerMemory(answerMemory));
            log.info("Registered Wren toolkit onto main agent '{}'", main.getName());

            // Register the Python sandbox-execution tool. See the class javadoc for why a
            // standalone proxy (instead of the agent's own filesystem instance) is sufficient.
            main.getDelegate()
                    .getToolkit()
                    .registerTool(new RunPythonTool(new SandboxBackedFilesystem(), artifactStore));
            log.info("Registered RunPythonTool onto main agent '{}'", main.getName());
        } catch (RuntimeException e) {
            log.warn("Failed to register DataAgent toolkit onto main agent: {}", e.getMessage());
        }
    }
}
