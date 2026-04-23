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
package io.agentscope.examples.chatbi.service;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.Msg;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that stops RouterAgent iteration after sub-agent tool calls complete.
 *
 * <p>When a sub-agent (like GuAgent, KnowledgeAgent, etc.) returns a complete answer,
 * the RouterAgent should directly forward that answer without performing additional
 * reasoning/summarization iterations.
 *
 * <p>This hook detects when a sub-agent tool call completes and stops further iteration,
 * ensuring the sub-agent's complete response is returned as-is.
 */
public class SubAgentCompleteHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(SubAgentCompleteHook.class);

    /**
     * Metadata key injected into the toolResultMsg when a sub-agent completes.
     * Used by ChatBiAgentService.eventToString() to distinguish this stop from
     * HITL (human-in-the-loop) stops, and to know that the ToolResultBlock text
     * should be forwarded directly to the frontend.
     */
    public static final String METADATA_SUB_AGENT_FINAL_ANSWER = "sub_agent_final_answer";

    /**
     * Set of sub-agent tool names that should trigger immediate completion.
     * When any of these tools finishes, RouterAgent stops and forwards the result directly
     * to the frontend without an extra LLM summarization call.
     *
     * <p>Includes {@code call_data_query_agent}: when the sub-agent's response contains
     * {@code [CONFIRM_PLAN]}, the marker is forwarded as-is so the frontend can render
     * confirm/decline buttons. When the user confirms, the RouterAgent re-dispatches to
     * this tool per the router system prompt §3.
     */
    private static final Set<String> SUB_AGENT_TOOLS =
            Set.of(
                    "call_data_query_agent",
                    "call_gu_agent",
                    "call_knowledge_agent",
                    "call_chat_agent",
                    "call_report_query_agent",
                    "call_report_schedule_agent",
                    "call_data_lineage_agent");

    @Override
    public int priority() {
        return 50; // High priority - run before other hooks
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent postActing) {
            String toolName = postActing.getToolUse().getName();

            if (SUB_AGENT_TOOLS.contains(toolName)) {
                log.info(
                        "SubAgentCompleteHook: Stopping RouterAgent iteration after {} completes",
                        toolName);

                // Mark the toolResultMsg with a special metadata flag so that
                // ChatBiAgentService can distinguish this stop from HITL stops
                // and forward the sub-agent text directly to the frontend.
                Msg original = postActing.getToolResultMsg();
                if (original != null) {
                    Map<String, Object> newMeta = new HashMap<>(original.getMetadata());
                    newMeta.put(METADATA_SUB_AGENT_FINAL_ANSWER, Boolean.TRUE);
                    postActing.setToolResultMsg(
                            Msg.builder()
                                    .id(original.getId())
                                    .name(original.getName())
                                    .role(original.getRole())
                                    .content(original.getContent())
                                    .metadata(newMeta)
                                    .build());
                }

                postActing.stopAgent();
            }
        }
        return Mono.just(event);
    }
}
