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
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that captures the original user message at sub-agent entry point
 * and persists it to the database, before the LLM has a chance to
 * reformulate it for tool calls.
 *
 * <p>ReActAgent synthesises tool-call parameters via the LLM, so the {@code query}
 * passed to a tool may differ from the user's original question. This hook ensures
 * the DB always stores the exact message the sub-agent received.
 */
public class SubAgentUserMessageHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(SubAgentUserMessageHook.class);
    private static final int PRIORITY = 5;

    private final String sessionId;
    private final String type;
    private final SubAgentMemoryService memoryService;

    public SubAgentUserMessageHook(
            String sessionId, String type, SubAgentMemoryService memoryService) {
        this.sessionId = sessionId;
        this.type = type;
        this.memoryService = memoryService;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent pre) {
            List<Msg> msgs = pre.getInputMessages();
            if (!msgs.isEmpty() && MsgRole.USER.equals(msgs.get(0).getRole())) {
                String question = msgs.get(0).getTextContent();
                memoryService.saveUserMessage(sessionId, type, question);
                log.debug(
                        "[SubAgentUserMessageHook] Saved original user message for session={}, "
                                + "type={}: {}",
                        sessionId,
                        type,
                        question);
            }
        }
        return Mono.just(event);
    }
}
