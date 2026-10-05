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
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.dataset.MdlWorkspaceReader;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.dataset.WrenCli;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wires a singleton {@link ModelingToolkit} onto the built-in {@code modeling-agent}'s toolkit at
 * startup (specs/013 M1, ADR 0024 D2): the modeling assistant gets ONLY the nine modeling tools —
 * a deliberately narrow surface, isolated from the query channel's toolkits.
 *
 * <p>Mirrors {@link DataToolkitRegistrar}: runs after {@link DataAgentBootstrap} has built every
 * agent, fails soft on errors so a missing tool slot does not stop the application from booting.
 */
@Component
public class ModelingToolkitRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ModelingToolkitRegistrar.class);

    public static final String MODELING_AGENT_ID = "modeling-agent";

    private final DataAgentBootstrap bootstrap;
    private final ModelingToolkit toolkit;

    public ModelingToolkitRegistrar(
            DataAgentBootstrap bootstrap,
            MdlSuggestionService suggestions,
            MdlPublishService mdlPublish,
            DatasetGroupService groupService,
            ConversationScopeRegistry conversationScopes,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader,
            WrenProperties wrenProps,
            WrenCli wrenCli) {
        this.bootstrap = bootstrap;
        this.toolkit =
                new ModelingToolkit(
                        suggestions,
                        mdlPublish,
                        groupService,
                        conversationScopes,
                        workspace,
                        reader,
                        wrenProps,
                        wrenCli);
    }

    /**
     * The singleton toolkit instance also serves the HITL file-change preview endpoint (specs/019
     * §5): the HTTP layer reuses the exact same gate code path that will execute the write.
     */
    public ModelingToolkit toolkit() {
        return toolkit;
    }

    @PostConstruct
    public void registerModelingToolkit() {
        HarnessAgent modeling = bootstrap.agents().get(MODELING_AGENT_ID);
        if (modeling == null) {
            log.warn(
                    "ModelingToolkitRegistrar: agent '{}' not found; skipping toolkit registration"
                            + " (agent is registered programmatically in DataAgentConfig)",
                    MODELING_AGENT_ID);
            return;
        }
        try {
            modeling.getDelegate().getToolkit().registerTool(toolkit);
            log.info("Registered Modeling toolkit onto agent '{}'", modeling.getName());
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to register Modeling toolkit onto '{}': {}",
                    MODELING_AGENT_ID,
                    e.getMessage());
        }
    }
}
