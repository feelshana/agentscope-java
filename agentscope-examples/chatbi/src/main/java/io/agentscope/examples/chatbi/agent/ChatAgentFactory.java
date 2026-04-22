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
import io.agentscope.examples.chatbi.service.ChatLogHook;
import io.agentscope.examples.chatbi.service.PerfTimingHook;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code ChatAgent} (intent: {@code ot}).
 *
 * <p>Plain LLM chat with no tools — handles general conversation,
 * common-knowledge Q&amp;A, and off-topic questions.
 */
@Component
public class ChatAgentFactory implements SubAgentFactory {

    private String sysPrompt;

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    @Override
    public ReActAgent create(AgentContext ctx) {
        return ReActAgent.builder()
                .name("ChatAgent")
                .description("处理闲聊/无关意图(ot)：日常对话、通识问答，直接自然语言回复。")
                .sysPrompt(sysPrompt)
                .model(ctx.streamModel())
                .memory(new InMemoryMemory())
                .maxIters(3)
                .hook(
                        new ChatLogHook(
                                ctx.sessionId() + "-ot",
                                "【ChatAgent】-> 处理闲聊/无关意图(ot)：日常对话、通识问答，直接自然语言回复",
                                sysPrompt))
                .hook(new PerfTimingHook(ctx.sessionId() + "-ot", "ChatAgent"))
                .build();
    }
}
