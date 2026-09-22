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

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Provides tombstone utilities for deleted sessions.
 *
 * <p>Auto-restore of orphaned transcripts is intentionally <b>disabled</b>. The previous boot-time
 * reconciler scanned workspace directories for leftover {@code .log.jsonl} files and re-registered
 * them into the session index — but it built gateKeys with the wrong format ({@code |t:<sessionId>}
 * instead of {@code |g:<conversationId>}), which caused deleted sessions to reappear in the sidebar
 * after restart and broke session-to-agent matching in {@link
 * io.agentscope.dataagent.web.api.SessionController}. Since the in-memory session index (persisted
 * to sessions.json) is the authoritative source, losing it means starting fresh — which is
 * acceptable and preferable to resurrecting deleted conversations.
 *
 * <p>The {@link #writeTombstone(Path)} helper is called by {@link
 * io.agentscope.dataagent.web.api.SessionController#deleteTranscriptFiles} to mark a session
 * directory as deleted, providing an extra safety net.
 */
@Component
public class SessionIndexReconciler {

    private static final Logger log = LoggerFactory.getLogger(SessionIndexReconciler.class);
    static final String TOMBSTONE_FILE = ".deleted";

    /**
     * Writes a tombstone marker into the given session directory. Called when a session is deleted
     * via the API so that any future reconciler pass (if re-enabled) will skip it.
     */
    public static void writeTombstone(Path sessionDir) {
        if (sessionDir == null) return;
        Path marker = sessionDir.resolve(TOMBSTONE_FILE);
        try {
            Files.createFile(marker);
        } catch (Exception e) {
            log.debug("Tombstone write skipped for {}: {}", sessionDir, e.getMessage());
        }
    }

    /** Returns {@code true} if the session directory carries a tombstone marker. */
    public static boolean isTombstoned(Path sessionDir) {
        if (sessionDir == null) return false;
        return Files.exists(sessionDir.resolve(TOMBSTONE_FILE));
    }
}
