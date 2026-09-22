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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Singleton in-memory event bus for tool-call events emitted by HarnessAgent hooks.
 *
 * <p>Consumers subscribe via {@link #subscribe(String)} filtering by session key.
 * Publishers call {@link #publish(ToolEvent)} from the hook when a tool call is about to execute.
 */
@Component
public class ToolEventBus {

    public static final String REQUEST_ID_CONTEXT_KEY = "toolEventRequestId";
    public static final String RUN_ID_CONTEXT_KEY = "toolEventRunId";
    public static final String PARENT_CALL_CONTEXT_KEY = "toolEventParentCallId";

    private final Sinks.Many<ToolEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    private final AtomicLong seqCounter = new AtomicLong();

    /** Publishes a tool-call event to all current subscribers, assigning a global sequence number. */
    public synchronized void publish(ToolEvent event) {
        long seq = seqCounter.getAndIncrement();
        sink.tryEmitNext(
                new ToolEvent(
                        event.sessionKey(),
                        event.eventType(),
                        event.toolName(),
                        event.toolCallId(),
                        event.data(),
                        event.requestId(),
                        event.runId(),
                        seq,
                        event.parentToolCallId()));
    }

    /**
     * Returns a {@link Flux} filtered to events matching the given session key.
     * Callers should manage the flux lifecycle (e.g. take-until-signal).
     */
    public Flux<ToolEvent> subscribe(String sessionKey) {
        return sink.asFlux().filter(e -> sessionKey.equals(e.sessionKey()));
    }

    /**
     * Returns the unfiltered event stream. Callers that cannot know the session key up-front
     * (e.g. before the gateway has registered the session on the first turn) can filter
     * lazily per event instead of committing to a key at subscription time.
     */
    public Flux<ToolEvent> events() {
        return sink.asFlux();
    }

    /**
     * A single tool-call or tool-result event.
     *
     * @param sessionKey the session key that produced this event
     * @param eventType {@code TOOL_CALL} or {@code TOOL_RESULT}
     * @param toolName the name of the tool
     * @param toolCallId the framework-assigned tool-call identifier for pairing call with result
     * @param data additional event data (input args or result text)
     * @param requestId the server-generated HTTP request identifier that owns this event
     * @param runId groups all tool events from a single acting phase (one agent turn)
     * @param seq global sequence number assigned by the bus at publish time for ordering
     * @param parentToolCallId the tool-call id of the parent tool that spawned this sub-agent call,
     *     or null for top-level tool calls
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolEvent(
            String sessionKey,
            String eventType,
            String toolName,
            String toolCallId,
            Map<String, Object> data,
            String requestId,
            String runId,
            long seq,
            String parentToolCallId) {

        public static ToolEvent toolCall(
                String sessionKey,
                String toolName,
                String toolCallId,
                Map<String, Object> input,
                String requestId,
                String runId) {
            return new ToolEvent(
                    sessionKey,
                    "TOOL_CALL",
                    toolName,
                    toolCallId,
                    input,
                    requestId,
                    runId,
                    0L,
                    null);
        }

        public static ToolEvent toolCall(
                String sessionKey,
                String toolName,
                String toolCallId,
                Map<String, Object> input,
                String requestId,
                String runId,
                String parentToolCallId) {
            return new ToolEvent(
                    sessionKey,
                    "TOOL_CALL",
                    toolName,
                    toolCallId,
                    input,
                    requestId,
                    runId,
                    0L,
                    parentToolCallId);
        }

        /**
         * Result event. {@code data} carries the single key {@code "result"} whose value is the
         * tool's text output; {@link io.agentscope.dataagent.web.api.ChatController} unwraps it
         * so the SSE {@code tool_result} frame carries plain text, matching the shape restored
         * from session history.
         */
        public static ToolEvent toolResult(
                String sessionKey,
                String toolName,
                String toolCallId,
                String result,
                String requestId,
                String runId) {
            return new ToolEvent(
                    sessionKey,
                    "TOOL_RESULT",
                    toolName,
                    toolCallId,
                    Map.of("result", result != null ? result : ""),
                    requestId,
                    runId,
                    0L,
                    null);
        }

        public static ToolEvent toolResult(
                String sessionKey,
                String toolName,
                String toolCallId,
                String result,
                String requestId,
                String runId,
                String parentToolCallId) {
            return new ToolEvent(
                    sessionKey,
                    "TOOL_RESULT",
                    toolName,
                    toolCallId,
                    Map.of("result", result != null ? result : ""),
                    requestId,
                    runId,
                    0L,
                    parentToolCallId);
        }
    }
}
