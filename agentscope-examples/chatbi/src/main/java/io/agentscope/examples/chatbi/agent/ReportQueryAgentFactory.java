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
import io.agentscope.examples.chatbi.client.SupersonicApiClient;
import io.agentscope.examples.chatbi.service.ChatLogHook;
import io.agentscope.examples.chatbi.service.PerfTimingHook;
import io.agentscope.examples.chatbi.tool.ReportQueryTool;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code ReportQueryAgent} (intent: {@code re}).
 *
 * <p>Recommends suitable reports, dashboards, or data screens based on the user's needs.
 */
@Component
public class ReportQueryAgentFactory implements SubAgentFactory {

    private final SupersonicApiClient supersonicClient;

    private String sysPrompt;

    public ReportQueryAgentFactory(SupersonicApiClient supersonicClient) {
        this.supersonicClient = supersonicClient;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    @Override
    public ReActAgent create(AgentContext ctx) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                new ReportQueryTool(supersonicClient, ctx.agentId(), ctx.supersonicToken()));

        return ReActAgent.builder()
                .name("ReportQueryAgent")
                .description("处理报表推荐意图(re)：根据用户需求推荐合适的报表、仪表盘或大屏。")
                .sysPrompt(sysPrompt)
                .model(ctx.streamModel())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxIters(5)
                .hook(
                        new ChatLogHook(
                                ctx.sessionId() + "-re",
                                "【ReportQueryAgent】-> 处理报表推荐意图(re)：根据用户需求推荐合适的报表、仪表盘或大屏",
                                sysPrompt))
                .hook(new PerfTimingHook(ctx.sessionId() + "-re", "ReportQueryAgent"))
                .build();
    }
}
