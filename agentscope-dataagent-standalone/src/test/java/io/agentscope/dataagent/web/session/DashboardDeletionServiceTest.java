package io.agentscope.dataagent.web.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.gateway.HarnessGateway;
import io.agentscope.dataagent.runtime.session.ConversationScopeRegistry;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.runtime.session.SessionTurnParser;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardDeletionServiceTest {
    @TempDir Path directory;
    final DashboardService dashboards = mock(DashboardService.class);
    final DataAgentBootstrap bootstrap = mock(DataAgentBootstrap.class);
    final HarnessGateway gateway = mock(HarnessGateway.class);
    final SessionAgentManager sessions = mock(SessionAgentManager.class);
    final AgentCatalogService catalog = mock(AgentCatalogService.class);
    final SessionHistoryService history = mock(SessionHistoryService.class);
    final SessionDeletionService deletion = mock(SessionDeletionService.class);
    final ConversationScopeRegistry scopes = mock(ConversationScopeRegistry.class);
    final List<SessionEntry> entries = new ArrayList<>();
    final String id = "dashboard-id";

    DashboardDeletionService service() {
        return new DashboardDeletionService(
                dashboards, bootstrap, catalog, history, deletion, scopes);
    }

    SessionEntry entry(String key, String user, String agent, String conversation) {
        return new SessionEntry(
                key,
                "internal-agent-uuid",
                key + "-session",
                null,
                SessionKind.MAIN,
                null,
                0,
                1,
                1,
                null,
                null,
                "chat|g:" + conversation + "|x:agentId=uca-" + user + "-" + agent,
                user);
    }

    @BeforeEach
    void setup() throws Exception {
        when(bootstrap.gateway()).thenReturn(gateway);
        when(gateway.sessionAgentManager()).thenReturn(sessions);
        when(sessions.allSessions()).thenAnswer(call -> List.copyOf(entries));
        when(catalog.peekGatewayAgentId(anyString(), eq(DashboardService.AGENT_ID)))
                .thenAnswer(call -> "uca-" + call.getArgument(0) + "-dashboard-agent");
        when(history.all(anyString()))
                .thenReturn(
                        SessionTurnParser.parse(
                                "{\"type\":\"message\",\"id\":\"turn-one\",\"role\":\"USER\",\"content\":\"生成\"}"));
        doAnswer(
                        call -> {
                            ((Runnable) call.getArgument(2)).run();
                            ((Consumer<Path>) call.getArgument(3)).accept(directory);
                            return null;
                        })
                .when(dashboards)
                .delete(eq("manager"), eq(id), any(), any());
    }

    @Test
    void nativeCleanupMatchesAgentAndConversationAndCapturesEveryParticipant() throws Exception {
        var alice = entry("a", "alice", "dashboard-agent", id);
        var bob = entry("b", "bob", "dashboard-agent", id);
        var differentAgent = entry("other-agent", "alice", "data-agent", id);
        var differentBoard = entry("other-board", "bob", "dashboard-agent", "other");
        entries.addAll(List.of(alice, bob, differentAgent, differentBoard));
        doAnswer(
                        call -> {
                            ((Runnable) call.getArgument(1)).run();
                            entries.remove(call.getArgument(0));
                            return null;
                        })
                .when(deletion)
                .delete(any(), any());
        service().delete("manager", id);
        assertThat(entries).containsExactly(differentAgent, differentBoard);
        verify(scopes).remove("alice", id, List.of("turn-one"), false);
        verify(scopes).remove("bob", id, List.of("turn-one"), true);
        assertThat(directory.resolve("deletion-plan.json")).exists();
    }

    @Test
    void persistedPlanRetriesAfterTheRuntimeEntryAlreadyDisappeared() throws Exception {
        var alice = entry("a", "alice", "dashboard-agent", id);
        entries.add(alice);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        call -> {
                            entries.clear();
                            if (attempts.getAndIncrement() == 0)
                                throw new IOException("storage offline");
                            ((Runnable) call.getArgument(1)).run();
                            return null;
                        })
                .when(deletion)
                .delete(any(), any());
        assertThatThrownBy(() -> service().delete("manager", id)).hasMessageContaining("重试删除");
        assertThat(entries).isEmpty();
        assertThat(directory.resolve("deletion-plan.json")).exists();
        service().delete("manager", id);
        assertThat(attempts.get()).isEqualTo(2);
        verify(scopes).remove("alice", id, List.of("turn-one"), true);
    }

    @Test
    void activeSessionIsRejectedBeforeBuildingADeletionPlan() {
        var active = entry("active", "alice", "dashboard-agent", id);
        entries.add(active);
        when(gateway.isSessionActive("active")).thenReturn(true);
        assertThatThrownBy(() -> service().delete("manager", id)).hasMessageContaining("仍在生成");
        assertThat(directory.resolve("deletion-plan.json")).doesNotExist();
    }
}
