package io.agentscope.dataagent.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import org.junit.jupiter.api.Test;

class SessionStateLifecycleTest {
    SessionAgentManager manager(AgentStateStore store) {
        return new SessionAgentManager(
                mock(DefaultAgentManager.class),
                AgentManagerConfig.defaults(),
                new SubagentRunRegistry(),
                null,
                store);
    }

    @Test
    void resetDeletesOnlyOldOwnerSlotAndNewConversationStartsEmpty() {
        var store = new InMemoryAgentStateStore();
        var manager = manager(store);
        var session = manager.registerMainSession("data-agent", null, "gate", "alice");
        store.save("alice", session.sessionId(), "agent_state", AgentState.builder().build());
        store.save("bob", session.sessionId(), "agent_state", AgentState.builder().build());
        assertThat(manager.resetSession(session.sessionKey())).isTrue();
        var reset = manager.getSession(session.sessionKey()).orElseThrow();
        assertThat(reset.sessionId()).isNotEqualTo(session.sessionId());
        assertThat(store.exists("alice", session.sessionId())).isFalse();
        assertThat(store.exists("alice", reset.sessionId())).isFalse();
        assertThat(store.exists("bob", session.sessionId())).isTrue();
    }

    @Test
    void removalCleansAllCheckpointKeysAndIsIdempotent() {
        var store = new InMemoryAgentStateStore();
        var manager = manager(store);
        var one = manager.registerMainSession("data-agent", "first", "one", "alice");
        var two = manager.registerMainSession("data-agent", "second", "two", "alice");
        for (String key : new String[] {"agent_state", "other_state"})
            store.save("alice", one.sessionId(), key, AgentState.builder().build());
        store.save("alice", two.sessionId(), "agent_state", AgentState.builder().build());
        manager.removeSession(one.sessionKey());
        manager.removeSession(one.sessionKey());
        assertThat(manager.getSession(one.sessionKey())).isEmpty();
        assertThat(manager.resolveSessionKey("first")).isEmpty();
        assertThat(store.exists("alice", one.sessionId())).isFalse();
        assertThat(store.exists("alice", two.sessionId())).isTrue();
    }

    @Test
    void failedCheckpointDeleteKeepsSessionForRetry() {
        var store = mock(AgentStateStore.class);
        var manager = manager(store);
        var session = manager.registerMainSession("data-agent", null, "gate", "alice");
        doThrow(new IllegalStateException("offline"))
                .when(store)
                .delete("alice", session.sessionId());
        assertThatThrownBy(() -> manager.removeSession(session.sessionKey()))
                .hasMessageContaining("offline");
        assertThat(manager.getSession(session.sessionKey())).isPresent();
        assertThatThrownBy(() -> manager.resetSession(session.sessionKey()))
                .hasMessageContaining("offline");
        assertThat(manager.getSession(session.sessionKey()).orElseThrow().sessionId())
                .isEqualTo(session.sessionId());
        doNothing().when(store).delete("alice", session.sessionId());
        manager.removeSession(session.sessionKey());
        assertThat(manager.getSession(session.sessionKey())).isEmpty();
    }

    @Test
    void maintenanceUsesTheSameCheckpointCleanupPath() {
        var store = new InMemoryAgentStateStore();
        var metadata = mock(SessionStore.class);
        var old =
                new SessionEntry(
                        "old",
                        "data-agent",
                        "old-state",
                        null,
                        SessionKind.MAIN,
                        null,
                        0,
                        1,
                        1,
                        null,
                        null,
                        "gate",
                        "alice");
        when(metadata.listAll()).thenReturn(java.util.List.of(SessionStore.StoredEntry.from(old)));
        store.save("alice", "old-state", "agent_state", AgentState.builder().build());
        var config =
                new AgentManagerConfig(
                        4,
                        8,
                        true,
                        256,
                        SessionResetPolicy.never(),
                        SessionMaintenanceConfig.enabled(1, 0));
        var manager =
                new SessionAgentManager(
                        mock(DefaultAgentManager.class),
                        config,
                        new SubagentRunRegistry(),
                        metadata,
                        store);
        assertThat(manager.getSession("old")).isEmpty();
        assertThat(store.exists("alice", "old-state")).isFalse();
        verify(metadata).remove("old");
    }
}
