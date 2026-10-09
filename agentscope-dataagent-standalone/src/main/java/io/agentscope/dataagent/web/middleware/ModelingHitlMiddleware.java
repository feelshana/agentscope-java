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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

/**
 * Native AgentScope HITL gate for semantic-modeling mutations.
 *
 * <p>The middleware is installed only on {@code modeling-agent}. It marks matching tool calls as
 * {@link ToolCallState#ASKING} in the persisted {@code AgentState.context}, emits the framework's
 * confirmation event, and stops with {@link GenerateReason#PERMISSION_ASKING}. A later request
 * carrying {@code Msg.METADATA_CONFIRM_RESULTS} resumes the same ReAct loop.
 */
public final class ModelingHitlMiddleware implements MiddlewareBase {

    private record RepairFeedback(String toolId, Msg message) {}

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx != null && input.msgs() != null) {
            for (Msg message : input.msgs()) {
                Object results =
                        message.getMetadata() == null
                                ? null
                                : message.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
                String text = message.getTextContent();
                if (message.getRole() == MsgRole.USER
                        && text != null
                        && !text.isBlank()
                        && !"[deny]".equals(text)
                        && results instanceof List<?> list) {
                    for (Object result : list) {
                        if (result instanceof ConfirmResult decision
                                && !decision.isConfirmed()
                                && decision.getToolCall() != null) {
                            ctx.put(
                                    RepairFeedback.class,
                                    new RepairFeedback(
                                            decision.getToolCall().getId(),
                                            Msg.builder()
                                                    .name("user")
                                                    .role(MsgRole.USER)
                                                    .textContent(text)
                                                    .build()));
                        }
                    }
                }
            }
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        RepairFeedback feedback = ctx == null ? null : ctx.get(RepairFeedback.class);
        if (feedback == null || ctx.getAgentState() == null) return next.apply(input);
        // Native confirmation consumes the incoming message without adding its text to context.
        // Add business feedback only after the framework has denied the correlated tool.
        boolean denied =
                ctx.getAgentState().getContext().stream()
                        .flatMap(msg -> msg.getContent().stream())
                        .anyMatch(
                                block ->
                                        block instanceof ToolResultBlock result
                                                && feedback.toolId().equals(result.getId())
                                                && result.getState() == ToolResultState.DENIED);
        if (!denied) return next.apply(input);
        ctx.put(RepairFeedback.class, null);
        ctx.getAgentState().contextMutable().add(feedback.message());
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(feedback.message());
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    /**
     * Modeling tools that can change persisted semantic state and therefore require approval.
     * specs/019 M3: the file write tools ({@code write_file}/{@code patch_file}) joined the gate
     * and the nine structured write tools (cubes/views/terms/rules) retired — their write side
     * moved into workspace files edited through the gated tools. specs/024: {@code
     * decide_relations} (batched relation decisions) joined the gate next to its single-edge
     * sibling; the {@code confirmModeling} whitelist in ChatController reads this set, so membership
     * here is the single source of truth for what reaches the HITL card. specs/035: {@code
     * create_view} (named-view pair write) joined the gate, replacing specs/034's {@code
     * propose_derived_model}, which retired with the view-first route. specs/036: {@code
     * suggest_relations} left the gate — it only recomputes the candidate queue without touching
     * persisted semantic state, so recompute no longer pops a confirmation card.
     */
    public static final Set<String> WRITE_TOOL_NAMES =
            Set.of(
                    "decide_relation",
                    "decide_relations",
                    "confirm_relation",
                    "reject_relation",
                    "add_relation",
                    "write_file",
                    "patch_file",
                    "create_view");

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        Set<String> resolved = resolvedToolIds(ctx);
        List<ToolUseBlock> pending =
                input.toolCalls().stream()
                        .filter(toolCall -> WRITE_TOOL_NAMES.contains(toolCall.getName()))
                        .filter(toolCall -> toolCall.getState() != ToolCallState.ALLOWED)
                        .filter(toolCall -> !resolved.contains(toolCall.getId()))
                        .toList();
        if (pending.isEmpty()) {
            return next.apply(input);
        }

        // The modeling script promises one decision at a time. If a model emits several
        // mutations in one response, pause on the first one; native pending-tool recovery will
        // present the remaining calls after this decision has been consumed.
        List<ToolUseBlock> asking =
                pending.stream()
                        .limit(1)
                        .map(tool -> tool.withState(ToolCallState.ASKING))
                        .toList();
        markAsking(ctx, asking);
        String replyId =
                ctx != null && ctx.getAgentState() != null
                        ? ctx.getAgentState().getReplyId()
                        : null;
        return Flux.just(
                new RequireUserConfirmEvent(replyId, asking),
                new RequestStopEvent("建模写操作需要用户确认", GenerateReason.PERMISSION_ASKING));
    }

    private static void markAsking(RuntimeContext ctx, List<ToolUseBlock> pending) {
        if (ctx == null || ctx.getAgentState() == null || pending.isEmpty()) {
            return;
        }
        Set<String> askingIds =
                pending.stream().map(ToolUseBlock::getId).collect(Collectors.toSet());
        List<Msg> context = ctx.getAgentState().contextMutable();
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            boolean matched =
                    msg.getContent().stream()
                            .anyMatch(
                                    block ->
                                            block instanceof ToolUseBlock toolUse
                                                    && askingIds.contains(toolUse.getId()));
            if (!matched) {
                continue;
            }
            List<ContentBlock> updated =
                    msg.getContent().stream()
                            .map(
                                    block ->
                                            block instanceof ToolUseBlock toolUse
                                                            && askingIds.contains(toolUse.getId())
                                                    ? toolUse.withState(ToolCallState.ASKING)
                                                    : block)
                            .toList();
            context.set(i, msg.withContent(updated));
        }
    }

    private static Set<String> resolvedToolIds(RuntimeContext ctx) {
        if (ctx == null || ctx.getAgentState() == null) return Set.of();
        return ctx.getAgentState().getContext().stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .filter(result -> !result.isSuspended())
                .map(ToolResultBlock::getId)
                .collect(Collectors.toSet());
    }
}
