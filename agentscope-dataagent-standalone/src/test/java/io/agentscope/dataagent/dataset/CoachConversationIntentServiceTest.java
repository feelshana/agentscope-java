package io.agentscope.dataagent.dataset;

import static io.agentscope.dataagent.dataset.CoachConversationIntentService.Action.CHAT;
import static io.agentscope.dataagent.dataset.CoachConversationIntentService.Action.CONFIRM_SQL;
import static io.agentscope.dataagent.dataset.CoachConversationIntentService.Action.REVIEW_PUBLICATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.dataset.CoachConversationIntentService.Phase;
import io.agentscope.dataagent.dataset.CoachConversationIntentService.Turn;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

class CoachConversationIntentServiceTest {
    @Test
    void sendsCurrentQuestionPhaseAndRecentTurnsWithoutTools() {
        Model model = mock(Model.class);
        when(model.stream(anyList(), eq(List.of()), isNull()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            assertThat(messages.get(1).getTextContent())
                                    .contains("每日净销售额", "REVIEW_SQL", "这版可以", "待核对 SQL", "按自然日汇总")
                                    .doesNotContain("已经过时");
                            return Flux.just(
                                    response("{\"action\":"), response("\"CONFIRM_SQL\"}"));
                        });
        assertThat(
                        service(model)
                                .interpret(
                                        "每日净销售额",
                                        Phase.REVIEW_SQL,
                                        List.of(
                                                new Turn("已经过时", ""),
                                                new Turn("改按下单日", "按自然日汇总"),
                                                new Turn("看一下这版", "待核对 SQL")),
                                        "这版可以")
                                .block())
                .isEqualTo(CONFIRM_SQL);
    }

    @Test
    void acceptsOnlyDeclaredActionsAndFallsBackOnInvalidOutputOrModelFailure() {
        assertThat(
                        CoachConversationIntentService.parse(
                                "```json\n{\"action\":\"REVIEW_PUBLICATION\"}\n```"))
                .isEqualTo(REVIEW_PUBLICATION);
        for (String response :
                List.of(
                        "",
                        "确认成功",
                        "{\"action\":\"PUBLISH\"}",
                        "{\"action\":\"CONFIRM_SQL\"} extra")) {
            assertThat(CoachConversationIntentService.parse(response)).isEqualTo(CHAT);
        }
        Model model = mock(Model.class);
        when(model.stream(anyList(), eq(List.of()), isNull()))
                .thenReturn(Flux.error(new IllegalStateException()));
        assertThat(service(model).interpret("每日净销售额", Phase.CONFIRMED, List.of(), "发布").block())
                .isEqualTo(CHAT);
    }

    private static CoachConversationIntentService service(Model model) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("model", model);
        return new CoachConversationIntentService(beans.getBeanProvider(Model.class));
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
