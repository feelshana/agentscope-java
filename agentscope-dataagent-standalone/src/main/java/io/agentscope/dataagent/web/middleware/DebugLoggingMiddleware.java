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
package io.agentscope.dataagent.web.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Debug middleware that logs the **raw** LLM input (full JSON of messages) and output (full
 * response text + tool calls) for every iteration to a dedicated {@code LLM_DEBUG} logger (routed
 * to {@code logs/LLM.log} via logback config).
 */
public class DebugLoggingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger("LLM_DEBUG");

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final AtomicInteger iterationCounter = new AtomicInteger(0);

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String userQuestion = extractLastUserMessage(input.msgs());
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        iterationCounter.set(0);
        log.info(
                "\n########## Agent invocation started ##########\n"
                        + "session={}\n"
                        + "userQuestion={}",
                sessionId,
                userQuestion);
        return next.apply(input)
                .doFinally(
                        sig ->
                                log.info(
                                        "\n########## Agent invocation finished ##########"
                                                + " session={}, signal={}, totalIterations={}",
                                        sessionId,
                                        sig,
                                        iterationCounter.get()));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        int iter = iterationCounter.incrementAndGet();
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        List<Msg> msgs = input.messages();

        // ── Raw input: serialize full message list as JSON ──
        String rawInputJson;
        try {
            rawInputJson = MAPPER.writeValueAsString(msgs);
        } catch (Exception e) {
            rawInputJson = "[serialization error: " + e.getMessage() + "]";
        }

        log.info(
                "\n========== LLM Call #{} ==========\n"
                        + "session={}\n"
                        + "msgCount={}\n"
                        + "----- RAW INPUT -----\n{}\n----- END INPUT -----",
                iter,
                sessionId,
                msgs != null ? msgs.size() : 0,
                rawInputJson);

        // ── Capture full response ──
        StringBuilder responseText = new StringBuilder();
        List<String> toolCallNames = new ArrayList<>();

        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent delta) {
                                responseText.append(delta.getDelta());
                            } else if (event instanceof ToolCallStartEvent tcStart) {
                                toolCallNames.add(tcStart.getToolCallName());
                            }
                        })
                .doOnComplete(
                        () -> {
                            String resp = responseText.toString();
                            log.info(
                                    "\n========== LLM Response #{} ==========\n"
                                            + "textLen={}\n"
                                            + "toolCalls={}\n"
                                            + "toolNames={}\n"
                                            + "----- RAW OUTPUT -----\n{}\n"
                                            + "----- END OUTPUT -----",
                                    iter,
                                    resp.length(),
                                    toolCallNames.size(),
                                    toolCallNames,
                                    resp);
                        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input.toolCalls() != null) {
            for (ToolUseBlock tc : input.toolCalls()) {
                String inputJson;
                try {
                    inputJson = MAPPER.writeValueAsString(tc.getInput());
                } catch (Exception e) {
                    inputJson = String.valueOf(tc.getInput());
                }
                log.info(
                        "\n----- Tool Execute: {} -----\n{}\n----- End Tool -----",
                        tc.getName(),
                        inputJson);
            }
        }
        return next.apply(input);
    }

    private static String extractLastUserMessage(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) return "(empty)";
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m.getRole() == MsgRole.USER) {
                return m.getTextContent();
            }
        }
        return "(no user message found)";
    }
}
