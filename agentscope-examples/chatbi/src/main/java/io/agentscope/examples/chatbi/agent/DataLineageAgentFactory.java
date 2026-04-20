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
import io.agentscope.examples.chatbi.client.DataLineageApiClient;
import io.agentscope.examples.chatbi.service.ChatLogHook;
import io.agentscope.examples.chatbi.service.DataLineageMemoryService;
import io.agentscope.examples.chatbi.service.DataLineageRoundSaveHook;
import io.agentscope.examples.chatbi.tool.DataLineageTool;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code DataLineageAgent} (intent: {@code dl}).
 *
 * <p>Queries data lineage, upstream/downstream table dependencies,
 * workflow and computation element dependencies.
 *
 * <p>This agent does NOT use a system prompt. Instead, {@link DataLineageTool} builds
 * a {@code final_prompt} from the raw lineage API result and injects it as the tool
 * response, which the LLM then uses to generate its final answer.
 *
 * <p>Each round's user question and assistant answer are persisted to the database via
 * {@link DataLineageMemoryService} and {@link DataLineageRoundSaveHook} respectively.
 * On subsequent calls within the same session, the history is passed as the
 * {@code memory} parameter to the lineage API.
 */
@Component
public class DataLineageAgentFactory implements SubAgentFactory {

    private final DataLineageApiClient lineageClient;
    private final DataLineageMemoryService memoryService;

    public DataLineageAgentFactory(
            DataLineageApiClient lineageClient,
            DataLineageMemoryService memoryService) {
        this.lineageClient = lineageClient;
        this.memoryService = memoryService;
    }

    @Override
    public ReActAgent create(AgentContext ctx) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DataLineageTool(
                lineageClient,
                ctx.projectId(),
                ctx.sessionId(),
                memoryService));

        return ReActAgent.builder()
                .name("DataLineageAgent")
                .description("处理数据血缘意图(dl)：查询数据血缘、上下游表依赖关系、工作流和运算元素依赖。")
                // No sysPrompt: the final_prompt is built by DataLineageTool from lineage data
                .model(ctx.streamModel())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxIters(5)
                .hook(new ChatLogHook(
                        ctx.sessionId() + "-dl",
                        "【DataLineageAgent】-> 处理数据血缘意图(dl)：查询数据血缘、上下游表依赖关系、工作流和运算元素依赖"))
                .hook(new DataLineageRoundSaveHook(ctx.sessionId(), memoryService))
                .build();
    }
}
