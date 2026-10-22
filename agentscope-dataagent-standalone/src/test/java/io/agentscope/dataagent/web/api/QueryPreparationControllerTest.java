package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.WrenQueryPreparation;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class QueryPreparationControllerTest {
    @Test
    void deniedAgentCannotReadPreparationState() {
        var guard = mock(AgentAccessGuard.class);
        var service = mock(WrenQueryPreparation.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(guard)
                .require("bob", "data-agent", Tier.RUN);
        var controller = new QueryPreparationController(guard, service);
        assertThatThrownBy(
                        () ->
                                controller
                                        .status(
                                                "a",
                                                new UsernamePasswordAuthenticationToken("bob", ""))
                                        .block())
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    @Test
    void knowledgeBaseDenialDoesNotExposeItsPreparation() {
        var guard = mock(AgentAccessGuard.class);
        var service = mock(WrenQueryPreparation.class);
        when(service.status("bob", "alice-group")).thenThrow(new DatasetException("无权访问", 403));
        var controller = new QueryPreparationController(guard, service);
        assertThatThrownBy(
                        () ->
                                controller
                                        .status(
                                                "alice-group",
                                                new UsernamePasswordAuthenticationToken("bob", ""))
                                        .block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }
}
