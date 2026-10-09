package io.agentscope.dataagent.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.dataagent.runtime.session.AgentManagerConfig;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class GatewayTurnLifecycleTest {
    @Test
    void restoredIndexChecksOwnerAndRejectsDeletedSession() {
        SessionAgentManager sessions = mock(SessionAgentManager.class);
        when(sessions.getConfig()).thenReturn(AgentManagerConfig.defaults());
        SessionEntry entry =
                new SessionEntry(
                        "stored",
                        "agent",
                        "runtime",
                        null,
                        SessionKind.MAIN,
                        null,
                        0,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        null,
                        null,
                        "gate",
                        "alice");
        when(sessions.allSessions()).thenReturn(List.of(entry));
        when(sessions.getSession("stored")).thenReturn(Optional.of(entry));
        HarnessGateway gateway = HarnessGateway.create(sessions);
        assertThat(gateway.findMainSessionKey("alice", "gate")).isEqualTo("stored");
        assertThat(gateway.findMainSessionKey("bob", "gate")).isNull();
        when(sessions.getSession("stored")).thenReturn(Optional.empty());
        assertThat(gateway.findMainSessionKey("alice", "gate")).isNull();
    }

    @Test
    void busyStreamFailsInsteadOfCompletingEmpty() throws Exception {
        SessionAgentManager sessions = mock(SessionAgentManager.class);
        when(sessions.getConfig()).thenReturn(AgentManagerConfig.defaults());
        when(sessions.allSessions()).thenReturn(List.of());
        HarnessGateway gateway = HarnessGateway.create(sessions);
        SessionTurnGate gate = mock(SessionTurnGate.class);
        when(gate.acquire("busy")).thenThrow(new TurnBusyException("busy"));
        ReflectionTestUtils.setField(gateway, "sessionTurnGate", gate);
        Supplier<Flux<AgentEvent>> execute =
                () -> {
                    throw new AssertionError("must not execute");
                };
        Flux<AgentEvent> stream =
                ReflectionTestUtils.invokeMethod(gateway, "withGatedStream", "busy", execute);
        StepVerifier.create(stream)
                .expectErrorMatches(e -> e.getMessage().contains("正在回答"))
                .verify();
    }
}
