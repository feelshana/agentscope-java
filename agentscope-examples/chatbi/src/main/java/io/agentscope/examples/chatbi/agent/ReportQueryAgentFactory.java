/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.chatbi.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.chatbi.client.ReportSearchApiClient;
import io.agentscope.examples.chatbi.service.ChatLogHook;
import io.agentscope.examples.chatbi.service.PerfTimingHook;
import io.agentscope.examples.chatbi.service.RoundSaveHook;
import io.agentscope.examples.chatbi.service.SubAgentMemoryService;
import io.agentscope.examples.chatbi.service.SubAgentUserMessageHook;
import io.agentscope.examples.chatbi.tool.ReportQueryTool;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code ReportQueryAgent} (intent: {@code re}).
 *
 * <p>Recommends suitable reports, dashboards, or data screens based on the user's needs.
 *
 * <p>Each round's user question and assistant answer are persisted to the database via
 * {@link SubAgentMemoryService} and {@link RoundSaveHook} with type "re".
 */
@Component
public class ReportQueryAgentFactory implements SubAgentFactory {

    private static final String TYPE_RE = "re";

    private final ReportSearchApiClient reportSearchApiClient;
    private final SubAgentMemoryService memoryService;

    private String sysPrompt;

    public ReportQueryAgentFactory(
            ReportSearchApiClient reportSearchApiClient, SubAgentMemoryService memoryService) {
        this.reportSearchApiClient = reportSearchApiClient;
        this.memoryService = memoryService;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    @Override
    public ReActAgent create(AgentContext ctx) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                new ReportQueryTool(
                        reportSearchApiClient,
                        ctx.projectId(),
                        ctx.reportName(),
                        ctx.dashboardName(),
                        ctx.sessionId(),
                        memoryService));

        return ReActAgent.builder()
                .name("ReportQueryAgent")
                .description("处理报表推荐意图(re)：根据用户需求推荐合适的报表、仪表盘或大屏。")
                .sysPrompt(sysPrompt)
                .model(ctx.streamModel())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxIters(5)
                .hook(new SubAgentUserMessageHook(ctx.sessionId(), TYPE_RE, memoryService))
                .hook(
                        new ChatLogHook(
                                ctx.sessionId() + "-re",
                                "【ReportQueryAgent】-> 处理报表推荐意图(re)：根据用户需求推荐合适的报表、仪表盘或大屏",
                                sysPrompt))
                .hook(new RoundSaveHook(ctx.sessionId(), TYPE_RE, memoryService))
                .hook(new PerfTimingHook(ctx.sessionId() + "-re", "ReportQueryAgent"))
                .build();
    }
}
