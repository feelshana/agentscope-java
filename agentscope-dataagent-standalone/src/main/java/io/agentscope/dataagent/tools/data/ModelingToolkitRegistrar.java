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
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlQuestionService;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.dataset.MdlWorkspaceReader;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.dataset.WrenCli;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.dataset.WrenSkillsLocator;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registers native file operations and the official CLI adapter on the modeling agent (ADR 0068).
 * The legacy toolkit is retained solely for existing HTTP callers and is never registered.
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
    private final WrenProjectFilesystem projectFilesystem;
    private final OfficialWrenToolkit officialTools;
    private final ModelingConfirmationTool confirmationTool;

    public ModelingToolkitRegistrar(
            DataAgentBootstrap bootstrap,
            MdlSuggestionService suggestions,
            MdlPublishService mdlPublish,
            DatasetGroupService groupService,
            ConversationScopeRegistry conversationScopes,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader,
            WrenProperties wrenProps,
            WrenCli wrenCli,
            MdlQuestionService questions,
            io.agentscope.dataagent.dataset.ModelingWorkflowService workflow,
            io.agentscope.dataagent.dataset.WrenProjectAccess projectAccess,
            io.agentscope.dataagent.dataset.ModelingWorkflowStore checks,
            FactQueryWindowPolicy windowPolicy,
            WrenSkillsLocator skillsLocator,
            io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets,
            io.agentscope.dataagent.dataset.ModelingConfirmationService confirmations) {
        this.bootstrap = bootstrap;
        this.confirmationTool = new ModelingConfirmationTool(projectAccess, confirmations);
        this.projectFilesystem = new WrenProjectFilesystem(projectAccess);
        // Same locator DataAgentConfig mounts from; when present the CLI skills channel closes.
        // Upload tables come from the same origin field as the runtime probe-boundary context.
        this.officialTools =
                new OfficialWrenToolkit(
                        projectAccess,
                        wrenCli,
                        wrenProps,
                        mdlPublish,
                        checks,
                        windowPolicy,
                        skillsLocator.locate().isPresent(),
                        groupId ->
                                datasets.findByGroupId(groupId).stream()
                                        .filter(d -> "upload".equals(d.getOrigin()))
                                        .map(d -> d.getName())
                                        .collect(java.util.stream.Collectors.toSet()));
        this.toolkit =
                new ModelingToolkit(
                        suggestions,
                        mdlPublish,
                        groupService,
                        conversationScopes,
                        workspace,
                        reader,
                        wrenProps,
                        wrenCli,
                        questions,
                        workflow);
    }

    /**
     * Retains legacy HTTP preview behavior for historical patch and batch cards.
     */
    public ModelingToolkit toolkit() {
        return toolkit;
    }

    public WrenProjectFilesystem projectFilesystem() {
        return projectFilesystem;
    }

    public String preflight(
            io.agentscope.core.agent.RuntimeContext context,
            io.agentscope.core.message.ToolUseBlock call) {
        return "wren_cli".equals(call.getName())
                ? officialTools.preflight(context, call)
                : projectFilesystem.preflight(context, call);
    }

    static void registerOfficialTools(
            io.agentscope.core.tool.Toolkit target,
            WrenProjectFilesystem files,
            OfficialWrenToolkit commands) {
        registerOfficialTools(target, files, commands, null);
    }

    static void registerOfficialTools(
            io.agentscope.core.tool.Toolkit target,
            WrenProjectFilesystem files,
            OfficialWrenToolkit commands,
            ModelingConfirmationTool confirmations) {
        target.registerTool(new io.agentscope.harness.agent.tool.FilesystemTool(files));
        target.registerTool(files);
        target.registerTool(commands);
        // spec092: the assistant asks the user only for genuinely ambiguous business choices.
        if (confirmations != null) target.registerTool(confirmations);
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
            registerOfficialTools(
                    modeling.getDelegate().getToolkit(),
                    projectFilesystem,
                    officialTools,
                    confirmationTool);
            log.info(
                    "Registered native file tools and official Wren CLI onto agent '{}'",
                    modeling.getName());
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to register Modeling toolkit onto '{}': {}",
                    MODELING_AGENT_ID,
                    e.getMessage());
        }
    }
}
