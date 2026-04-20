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
import io.agentscope.examples.chatbi.client.ConfluenceApiClient;
import io.agentscope.examples.chatbi.service.ChatLogHook;
import io.agentscope.examples.chatbi.tool.ConfluenceFilterTool;
import io.agentscope.examples.chatbi.tool.ConfluenceTool;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code GuAgent} (intent: {@code gu}).
 *
 * <p>Handles tool-usage intent: how to use big-data platform / HiMarket BI tools,
 * permission applications, report subscriptions, and other operational questions.
 */
@Component
public class GuAgentFactory implements SubAgentFactory {

    private final ConfluenceApiClient confluenceClient;

    private String sysPrompt;

    public GuAgentFactory(ConfluenceApiClient confluenceClient) {
        this.confluenceClient = confluenceClient;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    @Override
    public ReActAgent create(AgentContext ctx) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ConfluenceTool(confluenceClient));
        toolkit.registerTool(new ConfluenceFilterTool(ctx.rewriteModel()));

        return ReActAgent.builder()
                .name("GuAgent")
                .description(
                        "处理工具使用意图(gu)：大数据平台/红海分析云BI工具的使用方法、权限申请、报表订阅等操作类问题。")
                .sysPrompt(sysPrompt)
                .model(ctx.streamModel())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxIters(10)
                .hook(new ChatLogHook(
                        ctx.sessionId() + "-gu",
                        "【GuAgent】-> 处理工具使用意图(gu)：大数据平台/红海分析云BI工具的使用方法、权限申请、报表订阅等",
                        sysPrompt))
                .build();
    }
}
