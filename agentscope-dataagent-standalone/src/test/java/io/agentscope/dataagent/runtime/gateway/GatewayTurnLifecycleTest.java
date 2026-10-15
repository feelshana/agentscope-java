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
    void queuedTurnCannotOverwriteActiveScopeAndSecondTurnUsesItsOwnSnapshot() throws Exception {
        var sessions = mock(SessionAgentManager.class);
        when(sessions.getConfig()).thenReturn(AgentManagerConfig.defaults());
        var ctx =
                io.agentscope.harness.agent.gateway.MsgContext.defaultContext().withUserId("alice");
        var entry =
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
                        ctx.canonicalKey(),
                        "alice");
        when(sessions.allSessions()).thenReturn(List.of(entry));
        when(sessions.getSession("stored")).thenReturn(Optional.of(entry));
        when(sessions.viewSession("stored"))
                .thenReturn(
                        Optional.of(
                                io.agentscope.dataagent.runtime.session.SessionView.from(entry)));
        var gateway = HarnessGateway.create(sessions);
        var scopes = new io.agentscope.dataagent.web.session.ConversationScopeRegistry();
        gateway.setConversationScopes(scopes);
        var firstRunning = new java.util.concurrent.CountDownLatch(1);
        var secondQueued = new java.util.concurrent.CountDownLatch(1);
        var acquired = new java.util.concurrent.atomic.AtomicInteger();
        var delegate = new io.agentscope.harness.agent.gateway.LocalSessionTurnGate();
        SessionTurnGate gate = mock(SessionTurnGate.class);
        when(gate.acquire(ctx.canonicalKey()))
                .thenAnswer(
                        inv -> {
                            if (acquired.incrementAndGet() == 2) secondQueued.countDown();
                            return delegate.acquire(ctx.canonicalKey());
                        });
        ReflectionTestUtils.setField(gateway, "sessionTurnGate", gate);
        var agent = mock(io.agentscope.harness.agent.HarnessAgent.class);
        when(agent.getAgentId()).thenReturn("agent");
        gateway.bindMainAgent(agent);
        var releaseFirst = reactor.core.publisher.Sinks.<AgentEvent>empty();
        when(agent.streamEvents(
                        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        inv -> {
                            var rtc = (io.agentscope.core.agent.RuntimeContext) inv.getArgument(1);
                            var scope = rtc.get(io.agentscope.dataagent.dataset.DatasetScope.class);
                            assertThat(scopes.get("alice", "runtime")).isEqualTo(scope.groupIds());
                            if (scope.groupIds().contains("g1")) {
                                firstRunning.countDown();
                                return releaseFirst.asMono().flux();
                            }
                            return Flux.empty();
                        });
        var accepted = new java.util.concurrent.CopyOnWriteArrayList<String>();
        java.util.function.Function<String, io.agentscope.core.agent.RuntimeContext> rtc =
                id ->
                        io.agentscope.core.agent.RuntimeContext.builder()
                                .userId("alice")
                                .put(
                                        io.agentscope.dataagent.dataset.DatasetScope.class,
                                        new io.agentscope.dataagent.dataset.DatasetScope(
                                                "alice", List.of(id)))
                                .put(
                                        io.agentscope.dataagent.dataset.ChatTurnScope.class,
                                        new io.agentscope.dataagent.dataset.ChatTurnScope(
                                                () -> accepted.add(id)))
                                .build();
        var first =
                gateway.runStream(ctx, List.of(), null, rtc.apply("g1"), null)
                        .collectList()
                        .toFuture();
        assertThat(firstRunning.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var second =
                gateway.runStream(ctx, List.of(), null, rtc.apply("g2"), null)
                        .collectList()
                        .toFuture();
        try {
            assertThat(secondQueued.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(accepted).containsExactly("g1");
            assertThat(scopes.get("alice", "runtime")).containsExactly("g1");
            releaseFirst.tryEmitEmpty();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(accepted).containsExactly("g1", "g2");
            assertThat(scopes.get("alice", "runtime")).containsExactly("g2");
        } finally {
            releaseFirst.tryEmitEmpty();
            first.cancel(true);
            second.cancel(true);
        }
    }

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
