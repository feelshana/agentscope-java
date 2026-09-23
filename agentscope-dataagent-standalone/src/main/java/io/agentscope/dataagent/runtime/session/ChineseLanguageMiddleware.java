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
package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;

/**
 * 在系统提示词最末尾追加中文语言强制指令。
 *
 * <p>框架层系统提示词（AgentStateStore Context、Domain Knowledge 等）为英文，
 * 会导致 LLM 倾向用英文输出中间叙述。本中间件在最后位置用中文覆盖该倾向，
 * 确保工具调用过程中的叙述和最终回复均为简体中文。
 */
public final class ChineseLanguageMiddleware implements HarnessRuntimeMiddleware {

    private static final String LANGUAGE_DIRECTIVE =
            """

            ## 语言规范（最高优先级）
            你的所有输出必须使用简体中文，包括但不限于：
            - 工具调用前的叙述与解释（如"正在查询…"、"数据已获取，接下来分析…"）
            - 中间推理过程的文字说明
            - 最终回复的全部内容
            - 图表标题、轴标签、图例
            - 代码注释
            禁止使用英文进行推理叙述。工具名称和参数保持原样，但周围的说明文字必须是中文。
            """;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt != null ? currentPrompt : "";
        return Mono.just(base + LANGUAGE_DIRECTIVE);
    }
}
