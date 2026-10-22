package io.agentscope.dataagent.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class QuestionDateGateTest {
    private final WrenQueryAccess access = mock(WrenQueryAccess.class);
    private final MdlCatalog catalog = mock(MdlCatalog.class);
    private final Model model = mock(Model.class);
    private final QuestionDateGate gate = new QuestionDateGate(access, catalog);

    private RuntimeContext context() {
        var ctx =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("s")
                        .put(DatasetScope.class, new DatasetScope("alice", List.of("g")))
                        .build();
        var group = new DatasetGroupEntity("g", "alice", "demo", "");
        group.setMdlVersion(1);
        when(access.resolve(any(), eq("g"))).thenReturn(group);
        when(catalog.dateGateContext("g")).thenReturn(Map.of("models", List.of()));
        return ctx;
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static final String ASK =
            """
            {"status":"ASK_DATE","dataAccess":true,"needsDate":true,"start":"","endExclusive":"","dateMeaning":"","message":"请指定统计起止日期，目前尚未查询。"}
            """;

    @Test
    void clarificationUsesNativeLifecycleAndDoesNotDiscoverWren() {
        var ctx = context();
        var gateway = mock(WrenQueryGateway.class);
        when(model.getModelName()).thenReturn("fixture");
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(response(ASK)));
        var agent =
                ReActAgent.builder()
                        .name("date-test")
                        .model(model)
                        .middleware(gate)
                        .middleware(
                                new WrenScopedToolkitMiddleware(
                                        access, gateway, mock(WrenPublishedProject.class), catalog))
                        .build();
        Msg answer = agent.call(List.of(user("访问最多的报表是什么？")), ctx).block();
        assertThat(answer.getTextContent()).contains("请指定统计起止日期");
        verifyNoInteractions(gateway);
        verify(model, times(1)).stream(anyList(), anyList(), any());
        assertThat(ctx.getAgentState().getContext())
                .anyMatch(m -> m.getRole() == MsgRole.USER && m.getTextContent().contains("访问最多"));
        assertThat(ctx.getAgentState().getContext())
                .anyMatch(
                        m ->
                                m.getRole() == MsgRole.ASSISTANT
                                        && m.getTextContent().contains("起止日期"));
    }

    @Test
    void historyContainsClarificationAndDateReplyWithoutRewritingOrMetadata() {
        var question = user("访问最多的报表是什么？");
        var ask = Msg.builder().role(MsgRole.ASSISTANT).textContent("请指定起止日期").build();
        var reply = user("2026年10月1日至7日");
        var history = QuestionDateGate.history(List.of(question, ask, reply));
        assertThat(history)
                .extracting(m -> m.get("text"))
                .containsExactly("访问最多的报表是什么？", "请指定起止日期", "2026年10月1日至7日");
        assertThat(question.getMetadata()).isNullOrEmpty();
    }

    @Test
    void validRangeIsInjectedAndGateRunsOnlyOncePerInvocation() {
        var ctx = context();
        String json =
                """
                {"status":"PASS","dataAccess":true,"needsDate":true,"start":"2026-10-01T00:00:00","endExclusive":"2026-10-08T00:00:00","dateMeaning":"访问明细.access_time","message":"使用指定日期。"}
                """;
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(response(json)));
        var input = new ModelCallInput(List.of(user("10月1日至7日")), List.of(), null, model);
        for (int i = 0; i < 2; i++)
            gate.onModelCall(
                            null,
                            ctx,
                            input,
                            actual -> {
                                assertThat(actual.messages())
                                        .anyMatch(m -> m.getTextContent().contains("2026-10-08"));
                                assertThat(actual.messages().get(0))
                                        .isSameAs(input.messages().get(0));
                                return Flux.empty();
                            })
                    .blockLast();
        verify(model, times(1)).stream(anyList(), anyList(), any());
    }

    @Test
    void invalidModelOutputAndFailureAskWithoutContinuing() {
        for (String json :
                List.of(
                        "bad",
                        "{\"status\":\"PASS\",\"message\":\"ok\"}",
                        "{\"status\":\"PASS\",\"dataAccess\":true,\"needsDate\":true,\"message\":\"ok\",\"start\":\"2026-10-08T00:00:00\",\"endExclusive\":\"2026-10-01T00:00:00\",\"dateMeaning\":\"time\"}"))
            assertThat(QuestionDateGate.parse(json).allowed()).isFalse();
        var ctx = context();
        when(model.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.error(new RuntimeException("provider failed")));
        AtomicBoolean next = new AtomicBoolean();
        var events =
                gate.onModelCall(
                                null,
                                ctx,
                                new ModelCallInput(List.of(user("统计")), List.of(), null, model),
                                in -> {
                                    next.set(true);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();
        assertThat(next).isFalse();
        assertThat(events).hasSize(1);
    }

    @Test
    void emptySelectionSkipsGateModelAndForeignSelectionFailsBeforeModel() {
        var empty =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("s")
                        .put(DatasetScope.class, new DatasetScope("alice", List.of()))
                        .build();
        var input = new ModelCallInput(List.of(user("你好")), List.of(), null, model);
        AtomicBoolean next = new AtomicBoolean();
        gate.onModelCall(
                        null,
                        empty,
                        input,
                        in -> {
                            next.set(true);
                            return Flux.empty();
                        })
                .blockLast();
        assertThat(next).isTrue();
        verifyNoInteractions(model, access, catalog);
        var ctx = context();
        when(access.resolve(ctx, "g")).thenThrow(new DatasetException("未授权", 403));
        assertThatThrownBy(() -> gate.onModelCall(null, ctx, input, in -> Flux.empty()).blockLast())
                .isInstanceOf(DatasetException.class);
        verifyNoInteractions(model);
    }
}
