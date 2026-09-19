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
package io.agentscope.dataagent.web.toolbus;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * {@link MiddlewareBase} implementation that publishes tool-call events to {@link ToolEventBus}
 * before each tool-call execution, and tool-<em>result</em> events once execution finishes,
 * enabling real-time SSE streaming of both in {@link
 * io.agentscope.dataagent.web.api.ChatController}.
 *
 * <p>Result text is accumulated from {@link ToolResultTextDeltaEvent} chunks keyed by
 * {@code toolCallId} and published as a single {@code TOOL_RESULT} event when the matching {@link
 * ToolResultEndEvent} arrives — including error states, so the UI never leaves a tool block
 * stuck on "Running…".
 *
 * <p>The session key is derived from {@link RuntimeContext#getSessionId()} (falling back to
 * {@link RuntimeContext#getUserId()}) on the in-flight call, accessed via the agent's
 * {@code getRuntimeContext()}.
 */
public class ToolNotificationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolNotificationMiddleware.class);

    private final ToolEventBus bus;

    public ToolNotificationMiddleware(ToolEventBus bus) {
        this.bus = bus;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        String sessionKey = resolveSessionKey(agent, ctx);
        String requestId = ctx == null ? null : ctx.get(ToolEventBus.REQUEST_ID_CONTEXT_KEY);
        String runId = UUID.randomUUID().toString().substring(0, 12);
        Map<String, String> parentMap = new HashMap<>();
        if (sessionKey != null && input.toolCalls() != null) {
            for (ToolUseBlock tu : input.toolCalls()) {
                Map<String, Object> inputData = new LinkedHashMap<>();
                if (tu.getInput() != null) {
                    inputData.putAll(tu.getInput());
                }
                String parentId = extractParentToolCallId(tu);
                if (parentId != null) {
                    parentMap.put(tu.getId(), parentId);
                }
                try {
                    bus.publish(
                            ToolEventBus.ToolEvent.toolCall(
                                    sessionKey,
                                    tu.getName(),
                                    tu.getId(),
                                    inputData,
                                    requestId,
                                    runId,
                                    parentId));
                    log.debug(
                            "Published TOOL_CALL event: session={}, tool={}, runId={}, parent={}",
                            sessionKey,
                            tu.getName(),
                            runId,
                            parentId);
                } catch (Exception e) {
                    log.debug(
                            "Failed to publish tool event for {}: {}",
                            tu.getName(),
                            e.getMessage());
                }
            }
        }
        if (sessionKey == null) {
            return next.apply(input);
        }
        Map<String, StringBuilder> resultText = new HashMap<>();
        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof ToolResultTextDeltaEvent delta) {
                                resultText
                                        .computeIfAbsent(
                                                delta.getToolCallId(), k -> new StringBuilder())
                                        .append(delta.getDelta());
                            } else if (event instanceof ToolResultEndEvent end) {
                                String text =
                                        resultText
                                                .getOrDefault(
                                                        end.getToolCallId(), new StringBuilder())
                                                .toString();
                                try {
                                    bus.publish(
                                            ToolEventBus.ToolEvent.toolResult(
                                                    sessionKey,
                                                    end.getToolCallName(),
                                                    end.getToolCallId(),
                                                    text,
                                                    requestId,
                                                    runId,
                                                    parentMap.get(end.getToolCallId())));
                                    log.info(
                                            "Published TOOL_RESULT: session={}, tool={}, runId={},"
                                                    + " len={}, preview=[{}]",
                                            sessionKey,
                                            end.getToolCallName(),
                                            runId,
                                            text.length(),
                                            text.substring(0, Math.min(text.length(), 300)));
                                } catch (Exception e) {
                                    log.debug(
                                            "Failed to publish tool result event for {}: {}",
                                            end.getToolCallName(),
                                            e.getMessage());
                                }
                            }
                        })
                .contextWrite(context -> context.put(ToolEventBus.RUN_ID_CONTEXT_KEY, runId));
    }

    private static String extractParentToolCallId(ToolUseBlock tu) {
        if (tu.getMetadata() == null) return null;
        Object val = tu.getMetadata().get("parentToolCallId");
        return val instanceof String s ? s : null;
    }

    private static String resolveSessionKey(Agent agent, RuntimeContext ctx) {
        if (ctx == null) return null;
        if (ctx.getSessionId() != null) {
            return ctx.getSessionId();
        }
        return ctx.getUserId();
    }
}
