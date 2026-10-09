/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.session;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.web.artifact.ArtifactStore;
import io.agentscope.dataagent.web.persistence.jpa.SessionReadStateRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** One deletion path shared by the user API and automatic retention. */
@Service
public class SessionDeletionService {

    private final DataAgentBootstrap bootstrap;
    private final SessionAgentManager sessions;
    private final SessionHistoryService history;
    private final ArtifactStore artifacts;
    private final SessionReadStateRepository readStates;

    public SessionDeletionService(
            DataAgentBootstrap bootstrap,
            SessionHistoryService history,
            ArtifactStore artifacts,
            SessionReadStateRepository readStates) {
        this.bootstrap = bootstrap;
        this.sessions = bootstrap.gateway().sessionAgentManager();
        this.history = history;
        this.artifacts = artifacts;
        this.readStates = readStates;
    }

    /**
     * Deletes a complete session. The method is idempotent after the registry entry disappears;
     * orphan artifact cleanup remains the final compensation for a process crash between steps.
     */
    public void delete(SessionEntry entry) throws IOException {
        if (bootstrap.gateway().isSessionActive(entry.sessionKey())) {
            throw new SessionBusyException(entry.sessionKey());
        }
        try {
            bootstrap
                    .gateway()
                    .mutateIdleSession(
                            entry.sessionKey(),
                            () ->
                                    history.clear(
                                            entry.sessionKey(),
                                            () -> {
                                                deleteTranscriptFiles(entry);
                                                sessions.removeSession(entry.sessionKey());
                                            }));
        } catch (IllegalStateException e) {
            if (bootstrap.gateway().isSessionActive(entry.sessionKey())
                    || "请先停止当前回答".equals(e.getMessage())) {
                throw new SessionBusyException(entry.sessionKey());
            }
            throw e;
        }
        try {
            artifacts.deleteSession(entry.userId(), entry.sessionId());
        } finally {
            readStates.deleteByUserIdAndSessionKey(entry.userId(), entry.sessionKey());
        }
    }

    private void deleteTranscriptFiles(SessionEntry entry) {
        Path transcript = history.transcript(entry);
        if (transcript == null) return;
        try {
            String name = transcript.getFileName().toString();
            Files.deleteIfExists(transcript);
            if (name.endsWith(".log.jsonl")) {
                Files.deleteIfExists(
                        transcript.resolveSibling(name.replace(".log.jsonl", ".jsonl")));
            } else if (name.endsWith(".jsonl")) {
                Files.deleteIfExists(
                        transcript.resolveSibling(
                                name.substring(0, name.length() - 6) + ".log.jsonl"));
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "历史文件清理失败，请重试", e);
        }
    }

    public static final class SessionBusyException extends IllegalStateException {
        public SessionBusyException(String key) {
            super("Session is active: " + key);
        }
    }
}
