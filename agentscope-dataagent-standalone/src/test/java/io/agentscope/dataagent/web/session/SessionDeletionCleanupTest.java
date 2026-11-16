package io.agentscope.dataagent.web.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.gateway.HarnessGateway;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.artifact.ArtifactStore;
import io.agentscope.dataagent.web.persistence.jpa.SessionReadStateRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionDeletionCleanupTest {
    @TempDir Path home;

    @Test
    void attachmentFailureKeepsRuntimeAndLogsForRetryThenClearsBothTranscriptFiles()
            throws Exception {
        var bootstrap = mock(DataAgentBootstrap.class);
        var gateway = mock(HarnessGateway.class);
        var sessions = mock(SessionAgentManager.class);
        var history = mock(SessionHistoryService.class);
        var artifacts = mock(ArtifactStore.class);
        var reads = mock(SessionReadStateRepository.class);
        when(bootstrap.gateway()).thenReturn(gateway);
        when(gateway.sessionAgentManager()).thenReturn(sessions);
        var entry =
                new SessionEntry(
                        "key",
                        "agent",
                        "session",
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
        Path log = home.resolve("session.log.jsonl");
        Path context = home.resolve("session.jsonl");
        Files.writeString(log, "log");
        Files.writeString(context, "context");
        when(history.transcript(entry)).thenReturn(log);
        doAnswer(
                        call -> {
                            ((Runnable) call.getArgument(1)).run();
                            return null;
                        })
                .when(gateway)
                .mutateIdleSession(anyString(), any());
        doAnswer(
                        call -> {
                            ((Runnable) call.getArgument(1)).run();
                            return null;
                        })
                .when(history)
                .clear(anyString(), any());
        var service = new SessionDeletionService(bootstrap, history, artifacts, reads);
        doThrow(new IOException("unavailable"))
                .doNothing()
                .when(artifacts)
                .deleteSession("alice", "session");
        assertThatThrownBy(() -> service.delete(entry)).isInstanceOf(IOException.class);
        verify(sessions, never()).removeSession("key");
        assertThat(log).exists();
        assertThat(context).exists();
        var extra = new java.util.concurrent.atomic.AtomicBoolean();
        service.delete(entry, () -> extra.set(true));
        assertThat(log).doesNotExist();
        assertThat(context).doesNotExist();
        assertThat(extra).isTrue();
        verify(sessions).removeSession("key");
        verify(reads).deleteByUserIdAndSessionKey("alice", "key");
    }
}
