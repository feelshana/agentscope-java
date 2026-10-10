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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ModelingHitlMiddlewareTest {

    private final ModelingHitlMiddleware middleware = new ModelingHitlMiddleware();

    @Test
    void nativeReactLoopRepairsTechnicalFailureBeforeFirstHumanPrompt() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var model =
                new io.agentscope.core.model.ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "preflight-script";
                    }

                    @Override
                    protected Flux<io.agentscope.core.model.ChatResponse> doStream(
                            List<Msg> messages,
                            List<io.agentscope.core.model.ToolSchema> tools,
                            io.agentscope.core.model.GenerateOptions options) {
                        int index = calls.getAndIncrement();
                        if (index == 1) {
                            assertThat(
                                            messages.stream()
                                                    .flatMap(msg -> msg.getContent().stream())
                                                    .filter(ToolResultBlock.class::isInstance)
                                                    .map(ToolResultBlock.class::cast)
                                                    .anyMatch(
                                                            result ->
                                                                    result.getState()
                                                                            == ToolResultState
                                                                                    .ERROR))
                                    .isTrue();
                        }
                        assertThat(index).isLessThan(2);
                        return Flux.just(
                                io.agentscope.core.model.ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        tool(
                                                                index == 0
                                                                        ? "invalid-native"
                                                                        : "valid-native",
                                                                "write_file",
                                                                ToolCallState.PENDING)))
                                        .build());
                    }
                };
        var gate =
                new ModelingHitlMiddleware(
                        (ctx, call) -> call.getId().startsWith("invalid") ? "empty SQL" : null);
        var agent =
                io.agentscope.core.ReActAgent.builder()
                        .name("modeling-preflight-test")
                        .model(model)
                        .toolkit(new io.agentscope.core.tool.Toolkit())
                        .middleware(gate)
                        .enablePendingToolRecovery(true)
                        .build();
        var events =
                agent.streamEvents(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("build plan")
                                                .build()))
                        .collectList()
                        .block(java.time.Duration.ofSeconds(10));
        assertThat(calls.get()).isEqualTo(2);
        assertThat(events)
                .filteredOn(RequireUserConfirmEvent.class::isInstance)
                .singleElement()
                .satisfies(
                        event ->
                                assertThat(((RequireUserConfirmEvent) event).getToolCalls())
                                        .extracting(ToolUseBlock::getId)
                                        .containsExactly("valid-native"));
    }

    @Test
    void failedPreflightProducesRecoverableErrorWithoutHumanConfirmation() {
        ToolUseBlock write = tool("bad-plan", "write_file", ToolCallState.PENDING);
        ToolUseBlock read = tool("read", "list_files", ToolCallState.PENDING);
        AgentState state = AgentState.builder().sessionId("preflight").build();
        RuntimeContext context = RuntimeContext.builder().agentState(state).build();
        var gate = new ModelingHitlMiddleware((ctx, call) -> "SQL cannot be empty");
        var events =
                gate.onActing(
                                null,
                                context,
                                new ActingInput(List.of(write, read)),
                                input -> {
                                    assertThat(input.toolCalls()).containsExactly(read);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();
        assertThat(events).isEmpty();
        ToolResultBlock result = (ToolResultBlock) state.getContext().get(0).getContent().get(0);
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(result.getId()).isEqualTo("bad-plan");
        gate.onActing(
                        null,
                        context,
                        new ActingInput(List.of(write)),
                        input -> {
                            assertThat(input.toolCalls()).containsExactly(write);
                            return Flux.empty();
                        })
                .blockLast();
        assertThat(state.getContext()).hasSize(1);
    }

    @Test
    void unexpectedPreflightFailureAlsoReturnsErrorAndCorrectedPlanCanAsk() {
        AgentState state = AgentState.builder().sessionId("repair").build();
        RuntimeContext context = RuntimeContext.builder().agentState(state).build();
        var failed =
                new ModelingHitlMiddleware(
                        (ctx, call) -> {
                            throw new IllegalArgumentException("bad YAML");
                        });
        failed.onActing(
                        null,
                        context,
                        new ActingInput(
                                List.of(tool("invalid", "write_file", ToolCallState.PENDING))),
                        input -> Flux.empty())
                .blockLast();
        assertThat(((ToolResultBlock) state.getContext().get(0).getContent().get(0)).getState())
                .isEqualTo(ToolResultState.ERROR);
        var fixed = new ModelingHitlMiddleware((ctx, call) -> null);
        var events =
                fixed.onActing(
                                null,
                                context,
                                new ActingInput(
                                        List.of(
                                                tool(
                                                        "fixed",
                                                        "write_file",
                                                        ToolCallState.PENDING))),
                                input -> Flux.empty())
                        .collectList()
                        .block();
        assertThat(events).filteredOn(RequireUserConfirmEvent.class::isInstance).hasSize(1);
    }

    @Test
    void nativeDeniedCallDoesNotOpenTheSameConfirmationAgain() {
        ToolUseBlock write = tool("denied-write", "write_file", ToolCallState.ASKING);
        AgentState state =
                AgentState.builder()
                        .sessionId("denied-session")
                        .addMessage(Msg.builder().role(MsgRole.ASSISTANT).content(write).build())
                        .addMessage(
                                Msg.builder()
                                        .role(MsgRole.TOOL)
                                        .content(
                                                ToolResultBlock.text("Permission denied by user")
                                                        .withIdAndName(
                                                                write.getId(), write.getName())
                                                        .withState(ToolResultState.DENIED))
                                        .build())
                        .build();
        RuntimeContext context = RuntimeContext.builder().agentState(state).build();
        AtomicBoolean delegated = new AtomicBoolean();
        middleware
                .onActing(
                        null,
                        context,
                        new ActingInput(List.of(write)),
                        input -> {
                            delegated.set(true);
                            return Flux.empty();
                        })
                .blockLast();
        assertThat(delegated).isTrue();
    }

    @Test
    void repairFeedbackRequiresCorrelatedNativeDenialAndIsCallScoped() {
        ToolUseBlock write = tool("write-1", "write_file", ToolCallState.ASKING);
        AgentState state = AgentState.builder().sessionId("session-1").build();
        RuntimeContext context = RuntimeContext.builder().agentState(state).build();
        Msg request =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("预检失败：禁止 LIMIT，请修正")
                        .metadata(
                                Map.of(
                                        Msg.METADATA_CONFIRM_RESULTS,
                                        List.of(new ConfirmResult(false, write))))
                        .build();
        middleware
                .onAgent(null, context, new AgentInput(List.of(request)), input -> Flux.empty())
                .blockLast();
        ReasoningInput reasoning = new ReasoningInput(List.of(), List.of(), null);
        middleware
                .onReasoning(
                        null,
                        context,
                        reasoning,
                        input -> {
                            assertThat(input.messages()).isEmpty();
                            return Flux.empty();
                        })
                .blockLast();
        assertThat(state.getContext()).isEmpty();

        state.contextMutable()
                .add(
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.text("Permission denied by user")
                                                .withIdAndName("write-1", "write_file")
                                                .withState(ToolResultState.DENIED))
                                .build());
        middleware
                .onReasoning(
                        null,
                        context,
                        reasoning,
                        input -> {
                            assertThat(input.messages())
                                    .singleElement()
                                    .satisfies(
                                            message ->
                                                    assertThat(message.getTextContent())
                                                            .contains("禁止 LIMIT"));
                            return Flux.empty();
                        })
                .blockLast();
        assertThat(state.getContext()).hasSize(2);
        middleware
                .onReasoning(
                        null,
                        context,
                        reasoning,
                        input -> {
                            assertThat(input.messages()).isEmpty();
                            return Flux.empty();
                        })
                .blockLast();
        RuntimeContext other =
                RuntimeContext.builder()
                        .agentState(AgentState.builder().sessionId("other").build())
                        .build();
        middleware
                .onReasoning(
                        null,
                        other,
                        reasoning,
                        input -> {
                            assertThat(input.messages()).isEmpty();
                            return Flux.empty();
                        })
                .blockLast();
        assertThat(other.getAgentState().getContext()).isEmpty();
    }

    @Test
    void pausesBeforeFirstMutationAndPersistsAskingState() {
        ToolUseBlock first = tool("write-1", "write_file", ToolCallState.PENDING);
        ToolUseBlock second = tool("write-2", "patch_file", ToolCallState.PENDING);
        AgentState state =
                AgentState.builder()
                        .sessionId("session-1")
                        .replyId("reply-1")
                        .addMessage(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(first, second)
                                        .build())
                        .build();
        RuntimeContext context = RuntimeContext.builder().agentState(state).build();
        AtomicBoolean delegated = new AtomicBoolean();

        List<AgentEvent> events =
                middleware
                        .onActing(
                                null,
                                context,
                                new ActingInput(List.of(first, second)),
                                input -> {
                                    delegated.set(true);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();

        assertThat(delegated).isFalse();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(RequireUserConfirmEvent.class);
        RequireUserConfirmEvent confirmation = (RequireUserConfirmEvent) events.get(0);
        assertThat(confirmation.getReplyId()).isEqualTo("reply-1");
        assertThat(confirmation.getToolCalls())
                .extracting(ToolUseBlock::getId)
                .containsExactly("write-1");
        assertThat(confirmation.getToolCalls().get(0).getState()).isEqualTo(ToolCallState.ASKING);
        assertThat(events.get(1)).isInstanceOf(RequestStopEvent.class);
        assertThat(((RequestStopEvent) events.get(1)).getGenerateReason())
                .isEqualTo(GenerateReason.PERMISSION_ASKING);

        List<ToolUseBlock> persisted =
                state.getContext().get(0).getContent().stream()
                        .filter(ToolUseBlock.class::isInstance)
                        .map(ToolUseBlock.class::cast)
                        .toList();
        assertThat(persisted)
                .extracting(ToolUseBlock::getState)
                .containsExactly(ToolCallState.ASKING, ToolCallState.PENDING);
    }

    @Test
    void readOnlyToolContinuesWithoutConfirmation() {
        ToolUseBlock read = tool("read-1", "list_modeling_state", ToolCallState.PENDING);
        AtomicBoolean delegated = new AtomicBoolean();

        List<AgentEvent> events =
                middleware
                        .onActing(
                                null,
                                RuntimeContext.empty(),
                                new ActingInput(List.of(read)),
                                input -> {
                                    delegated.set(true);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();

        assertThat(delegated).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    void allowedMutationContinuesAfterNativeConfirmation() {
        ToolUseBlock allowed = tool("write-1", "decide_relation", ToolCallState.ALLOWED);
        AtomicBoolean delegated = new AtomicBoolean();

        middleware
                .onActing(
                        null,
                        RuntimeContext.empty(),
                        new ActingInput(List.of(allowed)),
                        input -> {
                            delegated.set(true);
                            return Flux.empty();
                        })
                .blockLast();

        assertThat(delegated).isTrue();
    }

    @Test
    void gateCoversFileWritesAndRelationQueueButNotRetiredStructuredWrites() {
        // specs/019 M3: the gated surface is the file write tools plus the relation candidate
        // queue; the retired structured write tools must no longer pause (the tools no longer
        // exist on the agent). specs/035 added create_view — the view file-pair write.
        // specs/036 removed suggest_relations — recompute-only, no persisted semantic change.
        for (String name :
                new String[] {
                    "write_file",
                    "patch_file",
                    "create_view",
                    "decide_relation",
                    "decide_relations",
                    "confirm_relation",
                    "reject_relation",
                    "add_relation"
                }) {
            assertThat(ModelingHitlMiddleware.WRITE_TOOL_NAMES).contains(name);
        }
        for (String retired :
                new String[] {
                    "create_cube",
                    "update_cube",
                    "delete_cube",
                    "update_view",
                    "delete_view",
                    "create_term",
                    "delete_term",
                    "create_business_rule",
                    "propose_derived_model"
                }) {
            assertThat(ModelingHitlMiddleware.WRITE_TOOL_NAMES).doesNotContain(retired);
        }
    }

    @Test
    void recomputeOnlySuggestRelationsLeavesTheGate() {
        // specs/036: suggest_relations only recomputes the pending candidate queue (no persisted
        // semantic change), so it must not pop a confirmation card any more.
        assertThat(ModelingHitlMiddleware.WRITE_TOOL_NAMES).doesNotContain("suggest_relations");
    }

    private static ToolUseBlock tool(String id, String name, ToolCallState state) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(Map.of("group_id", "g1"))
                .state(state)
                .build();
    }
}
