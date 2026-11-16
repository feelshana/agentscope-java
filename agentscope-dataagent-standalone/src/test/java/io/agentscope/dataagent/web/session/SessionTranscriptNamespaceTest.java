package io.agentscope.dataagent.web.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.gateway.HarnessGateway;
import io.agentscope.dataagent.runtime.session.ConversationScopeRegistry;
import io.agentscope.dataagent.runtime.session.GroupScopedMemoryNamespace;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionMessageRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

class SessionTranscriptNamespaceTest {
    @TempDir Path home;

    @Test
    void persistedConversationScopeRestoresTheExecutionNamespace() throws Exception {
        var bootstrap = mock(DataAgentBootstrap.class);
        var gateway = mock(HarnessGateway.class);
        var agent = mock(HarnessAgent.class);
        var workspace = mock(WorkspaceManager.class);
        var scopes = new ConversationScopeRegistry();
        scopes.put("alice", "conversation", List.of("group"));
        Path expected = home.resolve("alice/kb-group/session.log.jsonl");
        Files.createDirectories(expected.getParent());
        Files.writeString(expected, "{}");
        when(bootstrap.gateway()).thenReturn(gateway);
        when(gateway.findAgent("dashboard-agent")).thenReturn(agent);
        when(agent.getName()).thenReturn("看板助手");
        when(agent.getWorkspaceManager()).thenReturn(workspace);
        when(workspace.resolveSessionLogFile(any(), eq("看板助手"), eq("sid")))
                .thenAnswer(
                        call -> {
                            RuntimeContext context = call.getArgument(0);
                            List<String> namespace =
                                    GroupScopedMemoryNamespace.INSTANCE.getNamespace(context);
                            return home.resolve(String.join("/", namespace))
                                    .resolve("session.log.jsonl");
                        });
        var history =
                new SessionHistoryService(
                        bootstrap,
                        mock(SessionRegistryRepository.class),
                        mock(SessionHistoryRepository.class),
                        mock(SessionMessageRepository.class),
                        mock(PlatformTransactionManager.class),
                        scopes);
        var entry =
                new SessionEntry(
                        "key",
                        "internal-id",
                        "sid",
                        null,
                        SessionKind.MAIN,
                        null,
                        0,
                        0,
                        0,
                        null,
                        null,
                        "c:chatui|g:conversation|x:agentId=dashboard-agent",
                        "alice");
        try {
            assertThat(history.transcript(entry)).isEqualTo(expected);
        } finally {
            history.stop();
        }
    }
}
