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
import io.agentscope.examples.chatbi.client.ReportScheduleApiClient;
import io.agentscope.examples.chatbi.service.ChatLogHook;
import io.agentscope.examples.chatbi.tool.ReportScheduleTool;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code ReportScheduleAgent} (intent: {@code cs}).
 *
 * <p>Queries the data refresh time (出数时间) for reports or dashboards.
 */
@Component
public class ReportScheduleAgentFactory implements SubAgentFactory {

    private final ReportScheduleApiClient reportScheduleApiClient;

    private String sysPrompt;

    public ReportScheduleAgentFactory(ReportScheduleApiClient reportScheduleApiClient) {
        this.reportScheduleApiClient = reportScheduleApiClient;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    @Override
    public ReActAgent create(AgentContext ctx) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReportScheduleTool(
                reportScheduleApiClient, ctx.reportId(), ctx.dashboardId(), ctx.supersonicToken()));

        return ReActAgent.builder()
                .name("ReportScheduleAgent")
                .description("处理出数时间意图(cs)：查询报表或仪表盘的数据刷新时间、出数时间点。")
                .sysPrompt(sysPrompt)
                .model(ctx.streamModel())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxIters(5)
                .hook(new ChatLogHook(
                        ctx.sessionId() + "-cs",
                        "【ReportScheduleAgent】-> 处理出数时间意图(cs)：查询报表或仪表盘的数据刷新时间、出数时间点",
                        sysPrompt))
                .build();
    }
}
