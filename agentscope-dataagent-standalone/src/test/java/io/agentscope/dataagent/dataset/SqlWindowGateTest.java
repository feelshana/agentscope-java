package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

class SqlWindowGateTest {
    private static final List<SqlWindowGate.Rule> RULES =
            List.of(
                    new SqlWindowGate.Rule("a", List.of("time1"), 30),
                    new SqlWindowGate.Rule("b", List.of("time2"), 20),
                    new SqlWindowGate.Rule("c", List.of("time3"), 7));
    private static final String ALLOW =
            """
            {"allowed":true,"tables":[
            {"table":"a","timeColumn":"time1","hasCondition":true,"windowDays":19,"maxWindowDays":30},
            {"table":"b","timeColumn":"time2","hasCondition":true,"windowDays":14,"maxWindowDays":20}],
            "reason":"所有已登记事实表均满足时间范围要求"}
            """;

    @Test
    void hangingModelTimesOutAndCancelsWithoutAllowingSql() {
        Model model = mock(Model.class);
        ObjectProvider<Model> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(model.stream(anyList(), anyList(), isNull()))
                .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        assertThat(
                        new SqlWindowGate(provider, java.time.Duration.ofMillis(10))
                                .check("SELECT SUM(x) FROM a", RULES)
                                .allowed())
                .isFalse();
        assertThat(cancelled).isTrue();
    }

    @Test
    void acceptsPerTableWindowsAndDoesNotApplyUnusedNarrowWindow() {
        var decision = SqlWindowGate.parse(ALLOW, RULES);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tables())
                .extracting(SqlWindowGate.TableCheck::windowDays)
                .containsExactly(19d, 14d);
    }

    @Test
    void exactMaximumIsAcceptedButEvenAFractionOverItIsRejected() {
        String exact =
                ALLOW.replace("\"windowDays\":19", "\"windowDays\":30")
                        .replace("\"windowDays\":14", "\"windowDays\":20");
        assertThat(SqlWindowGate.parse(exact, RULES).allowed()).isTrue();
        assertThat(
                        SqlWindowGate.parse(
                                        exact.replace("\"windowDays\":30", "\"windowDays\":30.01"),
                                        RULES)
                                .allowed())
                .isFalse();
    }

    @Test
    void validatesReturnedRowsAgainstTheirOwnRegistration() {
        for (String invalid :
                List.of(
                        ALLOW.replace("\"maxWindowDays\":20", "\"maxWindowDays\":30"),
                        ALLOW.replace("\"time2\"", "\"time1\""),
                        ALLOW.replace("\"table\":\"b\"", "\"table\":\"unregistered\""),
                        ALLOW.replace("\"windowDays\":14", "\"windowDays\":21"),
                        ALLOW.replace("\"windowDays\":14", "\"windowDays\":null"),
                        ALLOW.replace("\"hasCondition\":true", "\"hasCondition\":false")))
            assertThat(SqlWindowGate.parse(invalid, RULES).allowed()).isFalse();
    }

    @Test
    void preservesMissingConditionAndOverWindowDetails() {
        var missing =
                SqlWindowGate.parse(
                        ALLOW.replace("\"allowed\":true", "\"allowed\":false")
                                .replace(
                                        "\"hasCondition\":true,\"windowDays\":14",
                                        "\"hasCondition\":false,\"windowDays\":null"),
                        RULES);
        assertThat(missing.allowed()).isFalse();
        assertThat(missing.tables().get(1).hasCondition()).isFalse();
        var wide =
                SqlWindowGate.parse(
                        ALLOW.replace("\"allowed\":true", "\"allowed\":false")
                                .replace("\"windowDays\":14", "\"windowDays\":40"),
                        RULES);
        assertThat(wide.tables().get(1).windowDays()).isEqualTo(40);
    }

    @Test
    void verdictFollowsRowsWhenModelConclusionContradictsThem() {
        // Observed 2026-10-20: 90 days against a 90-day limit came back allowed=false.
        var equalLimit =
                SqlWindowGate.parse(
                        ALLOW.replace("\"allowed\":true", "\"allowed\":false")
                                .replace("\"windowDays\":19", "\"windowDays\":30"),
                        RULES);
        assertThat(equalLimit.allowed()).isTrue();
        assertThat(equalLimit.reason()).startsWith("逐表明细均未超限，按明细放行");
        assertThat(equalLimit.tables())
                .extracting(SqlWindowGate.TableCheck::windowDays)
                .containsExactly(30d, 14d);

        var overLimit =
                SqlWindowGate.parse(ALLOW.replace("\"windowDays\":14", "\"windowDays\":21"), RULES);
        assertThat(overLimit.allowed()).isFalse();
        assertThat(overLimit.reason()).startsWith("逐表明细存在缺少日期或超限，按明细拒绝");
        assertThat(overLimit.tables().get(1).windowDays()).isEqualTo(21);
    }

    @Test
    void emptyRowsKeepTheModelRefusalBecauseNothingCanBeVerified() {
        var refused =
                SqlWindowGate.parse(
                        "{\"tables\":[],\"reason\":\"无法确定使用的表\",\"allowed\":false}", RULES);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).isEqualTo("无法确定使用的表");
    }

    @Test
    void rejectsMalformedAndIncompleteJson() {
        for (String raw :
                List.of("not json", "{}", "{\"allowed\":true,\"tables\":[],\"reason\":\"\"}"))
            assertThat(SqlWindowGate.parse(raw, RULES).allowed()).isFalse();
        assertThat(SqlWindowGate.parse("```json\n" + ALLOW + "\n```", RULES).allowed()).isTrue();
    }

    @Test
    void unregisteredSqlMayHaveNoMatchedTables() {
        assertThat(
                        SqlWindowGate.parse(
                                        "{\"allowed\":true,\"tables\":[],\"reason\":\"未使用登记表\"}",
                                        RULES)
                                .allowed())
                .isTrue();
    }

    @Test
    void modelReceivesOriginalSqlAndChineseLogicalNamesOnly() {
        Model model = mock(Model.class);
        ObjectProvider<Model> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.stream(anyList(), anyList(), isNull()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            assertThat(messages.get(1).getTextContent())
                                    .contains("SELECT SUM(x.amount) FROM `订单` x", "业务日期");
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text(
                                                                            "{\"allowed\":true,\"tables\":[{\"table\":\"订单\",\"timeColumn\":\"业务日期\",\"hasCondition\":true,\"windowDays\":19,\"maxWindowDays\":30}],\"reason\":\"符合\"}")
                                                                    .build()))
                                            .build());
                        });
        assertThat(
                        new SqlWindowGate(provider)
                                .check(
                                        "SELECT SUM(x.amount) FROM `订单` x",
                                        List.of(new SqlWindowGate.Rule("订单", List.of("业务日期"), 30)))
                                .allowed())
                .isTrue();
    }

    @Test
    void emptyConfigurationSkipsModelAndFailureClosesGate() {
        ObjectProvider<Model> provider = mock(ObjectProvider.class);
        var gate = new SqlWindowGate(provider);
        assertThat(gate.check("SELECT 1", List.of()).allowed()).isTrue();
        verifyNoInteractions(provider);
        assertThat(gate.check("SELECT 1", RULES).allowed()).isFalse();
        Model model = mock(Model.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.stream(anyList(), anyList(), isNull()))
                .thenReturn(Flux.error(new IllegalStateException("offline")));
        assertThat(gate.check("SELECT 1", RULES).allowed()).isFalse();
    }
}
