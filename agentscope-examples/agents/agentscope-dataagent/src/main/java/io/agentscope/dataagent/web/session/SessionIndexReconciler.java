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
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Restores session index entries from on-disk transcripts at boot. Session metadata lives in an
 * in-memory index persisted to sessions.json; when that index is lost (workspace move, prune, or
 * a run from a different working directory) older conversations disappear from the sidebar even
 * though their transcripts remain. This reconciler re-registers any transcript file that has no
 * matching index entry so history survives restarts.
 */
@Component
public class SessionIndexReconciler {

    private static final Logger log = LoggerFactory.getLogger(SessionIndexReconciler.class);
    private static final String LOG_SUFFIX = ".log.jsonl";

    private final DataAgentBootstrap bootstrap;

    public SessionIndexReconciler(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @PostConstruct
    void reconcile() {
        SessionAgentManager sessionAgentManager = bootstrap.gateway().sessionAgentManager();
        String mainAgentId = bootstrap.loadedConfig().getMain();
        int restored = 0;
        for (Path root : candidateRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> users = Files.list(root)) {
                for (Path userDir : users.filter(Files::isDirectory).toList()) {
                    Path agentsDir = userDir.resolve("agents");
                    if (!Files.isDirectory(agentsDir)) {
                        continue;
                    }
                    try (Stream<Path> agentDirs = Files.list(agentsDir)) {
                        for (Path agentDir : agentDirs.filter(Files::isDirectory).toList()) {
                            Path sessDir = agentDir.resolve("sessions");
                            if (!Files.isDirectory(sessDir)) {
                                continue;
                            }
                            try (Stream<Path> files = Files.list(sessDir)) {
                                for (Path f :
                                        files.filter(
                                                        p ->
                                                                p.getFileName()
                                                                        .toString()
                                                                        .endsWith(LOG_SUFFIX))
                                                .toList()) {
                                    if (restoreOne(sessionAgentManager, mainAgentId, userDir, f)) {
                                        restored++;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Session reconcile skipped root {}: {}", root, e.getMessage());
            }
        }
        if (restored > 0) {
            log.info(
                    "SessionIndexReconciler: restored {} historical session(s) into the index",
                    restored);
        }
    }

    private boolean restoreOne(
            SessionAgentManager sessionAgentManager, String mainAgentId, Path userDir, Path file) {
        String fileName = file.getFileName().toString();
        String sessionId = fileName.substring(0, fileName.length() - LOG_SUFFIX.length());
        String userId = userDir.getFileName().toString();
        boolean exists =
                sessionAgentManager.allSessions().stream()
                        .anyMatch(
                                e -> userId.equals(e.userId()) && sessionId.equals(e.sessionId()));
        if (exists) {
            return false;
        }
        long lastActivity;
        try {
            lastActivity = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            lastActivity = System.currentTimeMillis();
        }
        String gateKey = "chatui|t:" + sessionId + "|x:agentId=" + mainAgentId + "|u:" + userId;
        sessionAgentManager.registerRestoredMainSession(
                mainAgentId, userId, sessionId, gateKey, file.toString(), lastActivity);
        return true;
    }

    private List<Path> candidateRoots() {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            roots.add(Paths.get(home, ".agentscope", "dataagent", "workspace"));
        }
        roots.add(Paths.get(System.getProperty("user.dir"), ".agentscope", "workspace"));
        return roots;
    }
}
