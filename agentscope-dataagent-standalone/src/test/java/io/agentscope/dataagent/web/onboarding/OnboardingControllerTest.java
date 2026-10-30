package io.agentscope.dataagent.web.onboarding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DatasetException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/** spec099: every endpoint acts on the authenticated user only. */
class OnboardingControllerTest {
    private final OnboardingService service = mock(OnboardingService.class);
    private final OnboardingController controller = new OnboardingController(service);
    private final UsernamePasswordAuthenticationToken bob =
            new UsernamePasswordAuthenticationToken("bob", null, List.of());

    @Test
    void everyEndpointUsesTheCurrentUser() {
        controller.get(bob).block();
        controller.dismiss(bob).block();
        controller.reopen(bob).block();
        controller.tip("follow_ups", bob).block();
        verify(service).status("bob");
        verify(service).dismiss("bob");
        verify(service).reopen("bob");
        verify(service).tipSeen("bob", "follow_ups");
    }

    @Test
    void anUnknownTipIsABadRequest() {
        when(service.tipSeen("bob", "x")).thenThrow(new DatasetException("未知的提示", 400));
        assertThatThrownBy(() -> controller.tip("x", bob).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
