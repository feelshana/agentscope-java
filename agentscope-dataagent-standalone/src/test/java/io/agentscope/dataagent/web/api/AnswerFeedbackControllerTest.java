package io.agentscope.dataagent.web.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.AnswerQueryMemory;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.web.session.SessionHistoryService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class AnswerFeedbackControllerTest {
    private final SessionController sessions = mock(SessionController.class);
    private final SessionHistoryService history = mock(SessionHistoryService.class);
    private final AnswerQueryMemory memory = mock(AnswerQueryMemory.class);
    private final AnswerFeedbackController controller =
            new AnswerFeedbackController(mock(AgentAccessGuard.class), sessions, history, memory);

    @Test
    void anotherUsersSessionIsRejectedBeforeReadingOrSavingQueries() {
        when(sessions.requireOwnedSession("data-agent", "foreign", "bob"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        var auth = new UsernamePasswordAuthenticationToken("bob", "unused");
        assertThrows(
                ResponseStatusException.class,
                () ->
                        controller
                                .feedback(
                                        "data-agent",
                                        "foreign",
                                        "answer",
                                        new AnswerFeedbackController.Request("UP"),
                                        auth)
                                .block());
        assertThrows(
                ResponseStatusException.class,
                () -> controller.status("data-agent", "foreign", "answer", auth).block());
        verifyNoInteractions(history, memory);
    }

    @Test
    void modelingAgentCannotWriteAnswerFeedback() {
        var auth = new UsernamePasswordAuthenticationToken("alice", "unused");
        assertThrows(
                DatasetException.class,
                () ->
                        controller
                                .feedback(
                                        "modeling-agent",
                                        "s",
                                        "a",
                                        new AnswerFeedbackController.Request("UP"),
                                        auth)
                                .block());
        verifyNoInteractions(sessions, history, memory);
    }
}
