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
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that saves the {@code DataLineageAgent}'s final assistant answer to the database.
 *
 * <p>Works in tandem with {@link DataLineageTool}: the tool saves the user question;
 * this hook saves the LLM's final answer.  Together they maintain the per-session
 * lineage conversation memory stored under {@code "<sessionId>-dl"}.
 *
 * <p>Only the last reasoning iteration (i.e. the final agent answer without any
 * {@code tool_use} blocks) is persisted.
 */
public class DataLineageRoundSaveHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(DataLineageRoundSaveHook.class);

    /** Lower priority number = runs earlier; 950 ensures it runs after ChatLogHook (900). */
    private static final int PRIORITY = 950;

    private final String sessionId;
    private final DataLineageMemoryService memoryService;

    public DataLineageRoundSaveHook(String sessionId, DataLineageMemoryService memoryService) {
        this.sessionId = sessionId;
        this.memoryService = memoryService;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent post) {
            Msg msg = post.getReasoningMessage();
            if (msg != null && MsgRole.ASSISTANT.equals(msg.getRole())) {
                // Only save when the assistant produces a pure text answer (no tool_use),
                // which indicates the final answer round.
                boolean hasToolUse = !msg.getContentBlocks(
                        io.agentscope.core.message.ToolUseBlock.class).isEmpty();
                if (!hasToolUse) {
                    List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
                    String answer = textBlocks.stream()
                            .map(TextBlock::getText)
                            .collect(Collectors.joining());
                    if (!answer.isBlank()) {
                        try {
                            memoryService.saveAssistantMessage(sessionId, answer);
                        } catch (Exception e) {
                            log.warn("[DataLineageRoundSaveHook] Failed to save assistant message"
                                    + " for session={}: {}", sessionId, e.getMessage());
                        }
                    }
                }
            }
        }
        return Mono.just(event);
    }
}
