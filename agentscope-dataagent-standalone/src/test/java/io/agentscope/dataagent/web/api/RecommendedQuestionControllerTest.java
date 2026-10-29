package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import io.agentscope.dataagent.dataset.RecommendedQuestionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/** spec098: QUERY users read the recommended question; others cannot see the knowledge base. */
class RecommendedQuestionControllerTest {
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final RecommendedQuestionService questions = mock(RecommendedQuestionService.class);
    private final RecommendedQuestionController controller =
            new RecommendedQuestionController(access, questions);

    @Test
    void queryUsersReadTheQuestion() {
        when(questions.get("g")).thenReturn(Optional.of("最近一个月每天的访问次数"));
        var body =
                controller
                        .get(
                                "g",
                                new UsernamePasswordAuthenticationToken(
                                        "bob", null, java.util.List.of()))
                        .block();
        assertThat(body.question()).isEqualTo("最近一个月每天的访问次数");
        verify(access).requireQuery("bob", "g");
    }

    @Test
    void strangersCannotSeeIt() {
        when(access.requireQuery("eve", "g")).thenThrow(new DatasetException("知识库不存在", 404));
        assertThatThrownBy(
                        () ->
                                controller
                                        .get(
                                                "g",
                                                new UsernamePasswordAuthenticationToken(
                                                        "eve", null, java.util.List.of()))
                                        .block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(questions, never()).get("g");
    }
}
