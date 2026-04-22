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
import io.agentscope.core.rag.integration.dify.DifyKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.chatbi.client.KnowledgeSearchClient;
import io.agentscope.examples.chatbi.service.ChatLogHook;
import io.agentscope.examples.chatbi.service.PerfTimingHook;
import io.agentscope.examples.chatbi.tool.KnowledgeSearchTool;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code KnowledgeAgent} (intent: {@code in/bu}).
 *
 * <p>Retrieves metric definitions, business knowledge and system knowledge
 * via Confluence full-text search + Dify RAG.
 */
@Component
public class KnowledgeAgentFactory implements SubAgentFactory {

    private final KnowledgeSearchClient knowledgeSearchClient;
    private final DifyKnowledge knowledge;
    private final RetrieveConfig retrieveConfig;

    private String sysPrompt;

    public KnowledgeAgentFactory(
            KnowledgeSearchClient knowledgeSearchClient,
            DifyKnowledge knowledge,
            RetrieveConfig retrieveConfig) {
        this.knowledgeSearchClient = knowledgeSearchClient;
        this.knowledge = knowledge;
        this.retrieveConfig = retrieveConfig;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    @Override
    public ReActAgent create(AgentContext ctx) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new KnowledgeSearchTool(knowledgeSearchClient));

        return ReActAgent.builder()
                .name("KnowledgeAgent")
                .description("处理知识类意图(in/bu)：检索指标口径定义、业务知识和系统知识。")
                .sysPrompt(sysPrompt)
                .model(ctx.streamModel())
                .memory(new InMemoryMemory())
                .knowledge(knowledge)
                .retrieveConfig(retrieveConfig)
                .toolkit(toolkit)
                .maxIters(5)
                .hook(
                        new ChatLogHook(
                                ctx.sessionId() + "-kb",
                                "【KnowledgeAgent】-> 处理知识类意图(in/bu)：检索指标口径定义、业务知识和系统知识",
                                sysPrompt))
                .hook(new PerfTimingHook(ctx.sessionId() + "-kb", "KnowledgeAgent"))
                .build();
    }
}
