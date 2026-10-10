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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.artifact.ArtifactStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionReadStateEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryEntity;
import io.agentscope.dataagent.web.session.SessionDeletionService;
import io.agentscope.dataagent.web.session.SessionHistoryService;
import io.agentscope.dataagent.web.session.SessionReadStateStore;
import io.agentscope.dataagent.web.session.SessionTurnParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AgentStateStore management endpoints, scoped to a specific agent.
 *
 * <ul>
 *   <li>{@code GET /api/agents/{agentId}/sessions/inbox} — paginated session list with previews
 *       and unread flags
 *   <li>{@code GET /api/agents/{agentId}/sessions/{key}} — structured turn-by-turn transcript
 *   <li>{@code POST /api/agents/{agentId}/sessions/{key}/reset} — clear conversation history
 *   <li>{@code PATCH /api/agents/{agentId}/sessions/{key}/read} — mark session read
 *   <li>{@code DELETE /api/agents/{agentId}/sessions/{key}} — drop the session entirely
 * </ul>
 *
 * <p>All endpoints require the session to belong to both the authenticated user <em>and</em> the
 * agent in the URL path; mismatches return 403.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final DataAgentBootstrap bootstrap;
    private final SessionAgentManager sessionAgentManager;
    private final SessionReadStateStore readStateStore;
    private final AgentCatalogService catalogService;
    private final SessionHistoryService history;
    private final SessionHistoryRepository historyRepository;
    private final ArtifactStore artifacts;
    private final SessionDeletionService deletion;

    public SessionController(
            DataAgentBootstrap builderBootstrap,
            SessionReadStateStore readStateStore,
            AgentCatalogService catalogService,
            SessionHistoryService history,
            SessionHistoryRepository historyRepository,
            ArtifactStore artifacts,
            SessionDeletionService deletion) {
        this.bootstrap = builderBootstrap;
        this.sessionAgentManager = builderBootstrap.gateway().sessionAgentManager();
        this.readStateStore = readStateStore;
        this.catalogService = catalogService;
        this.history = history;
        this.historyRepository = historyRepository;
        this.artifacts = artifacts;
        this.deletion = deletion;
    }

    public record InboxPage(List<InboxEntry> items, String nextCursor, boolean hasMore) {}

    @GetMapping("/inbox-page")
    public Mono<InboxPage> inbox(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) String cursor,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            int take = Math.max(1, Math.min(limit, 100));
                            long before = Long.MAX_VALUE;
                            String beforeKey = "";
                            if (cursor != null && !cursor.isBlank()) {
                                try {
                                    String raw =
                                            new String(
                                                    java.util.Base64.getUrlDecoder().decode(cursor),
                                                    StandardCharsets.UTF_8);
                                    int split = raw.indexOf('\n');
                                    before = Long.parseLong(raw.substring(0, split));
                                    beforeKey = raw.substring(split + 1);
                                } catch (RuntimeException e) {
                                    throw new ResponseStatusException(
                                            HttpStatus.BAD_REQUEST, "Invalid cursor");
                                }
                            }
                            String gatewayId = catalogService.peekGatewayAgentId(userId, agentId);
                            var rows =
                                    historyRepository.inbox(
                                            userId,
                                            gatewayId,
                                            unreadOnly,
                                            before,
                                            beforeKey,
                                            PageRequest.of(0, take + 1));
                            boolean more = rows.size() > take;
                            List<InboxEntry> items = new ArrayList<>();
                            for (var row : rows.subList(0, Math.min(take, rows.size()))) {
                                var entry = (SessionRegistryEntity) row[0];
                                var h = (SessionHistoryEntity) row[1];
                                var read = (SessionReadStateEntity) row[2];
                                long activity =
                                        h != null ? h.activityMs : entry.getLastActivityMs();
                                items.add(
                                        new InboxEntry(
                                                entry.getSessionKey(),
                                                entry.getSessionId(),
                                                entry.getAgentId(),
                                                extractConversationId(entry.getGateKey()),
                                                h != null ? h.title : null,
                                                entry.getLabel(),
                                                activity,
                                                h != null ? h.preview : null,
                                                activity
                                                        > (read != null
                                                                ? read.getLastReadAtMs()
                                                                : 0)));
                            }
                            String next = null;
                            if (more && !items.isEmpty()) {
                                var last = items.get(items.size() - 1);
                                next =
                                        java.util.Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(
                                                        (last.lastActivityMs()
                                                                        + "\n"
                                                                        + last.sessionKey())
                                                                .getBytes(StandardCharsets.UTF_8));
                            }
                            return new InboxPage(items, next, more);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Compatibility for clients opened before the paginated frontend was deployed. */
    @GetMapping("/inbox")
    public Mono<List<InboxEntry>> legacyInbox(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            Authentication auth) {
        return inbox(agentId, limit, unreadOnly, null, auth).map(InboxPage::items);
    }

    @GetMapping("/{key}/messages")
    public Mono<SessionHistoryService.MessagePage> messagePage(
            @PathVariable String agentId,
            @PathVariable String key,
            @RequestParam(required = false) Integer before,
            @RequestParam(defaultValue = "50") int limit,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            SessionEntry entry =
                                    requireOwnedSession(agentId, key, (String) auth.getPrincipal());
                            history.refresh(entry.sessionKey());
                            return history.page(entry.sessionKey(), before, limit);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{key}")
    public Mono<List<SessionTurnParser.TurnEntry>> turns(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            var entry =
                                    requireOwnedSession(agentId, key, (String) auth.getPrincipal());
                            history.refresh(entry.sessionKey());
                            return history.all(entry.sessionKey());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{key}/reset")
    public Mono<ResetResult> reset(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            SessionEntry entry = requireOwnedSession(agentId, key, userId);
                            if (bootstrap.gateway().isSessionActive(entry.sessionKey()))
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "请先停止当前回答，再删除或重置会话");
                            bootstrap
                                    .gateway()
                                    .mutateIdleSession(
                                            entry.sessionKey(),
                                            () ->
                                                    history.clear(
                                                            entry.sessionKey(),
                                                            () -> {
                                                                deleteTranscriptFiles(entry);
                                                                sessionAgentManager.resetSession(
                                                                        entry.sessionKey());
                                                            }));
                            artifacts.deleteSession(userId, entry.sessionId());
                            return new ResetResult(key, true);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/{key}/read")
    public Mono<ReadStateResult> markRead(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            SessionEntry entry = requireOwnedSession(agentId, key, userId);
                            long readAtMs = readStateStore.markRead(userId, entry.sessionKey());
                            return new ReadStateResult(key, readAtMs, false);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.<Void>fromRunnable(
                        () -> {
                            SessionEntry entry = requireOwnedSession(agentId, key, userId);
                            try {
                                deletion.delete(entry);
                            } catch (SessionDeletionService.SessionBusyException e) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "请先停止当前回答，再删除会话", e);
                            } catch (java.io.IOException e) {
                                throw new IllegalStateException("附件清理失败", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Removes the exact local log and checkpoint before unregistering a session. */
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
        } catch (Exception e) {
            log.warn("Failed to delete transcript for {}", entry.sessionKey(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "历史文件清理失败，请重试", e);
        }
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    /**
     * Resolves a path-level {@code key} to a {@link SessionEntry} owned by {@code userId} for the
     * URL {@code agentId}. {@code key} may be either the internal storage key (legacy callers) or
     * the conversationId surfaced via {@link InboxEntry#conversationId()} — the FE only ever sees
     * the latter for ChatGPT-style multi-session navigation.
     */
    SessionEntry requireOwnedSession(String agentId, String key, String userId) {
        SessionEntry entry =
                sessionAgentManager
                        .getSession(key)
                        .orElseGet(() -> findSessionByConversationId(agentId, key, userId));
        if (entry == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "AgentStateStore not found: " + key);
        }
        String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
        if (!Objects.equals(entry.userId(), userId)
                || !sessionMatchesAgent(entry, gatewayAgentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /**
     * Scans registered MAIN sessions for one whose {@code gateKey} carries the conversationId
     * (in the {@code |g:<key>} segment with PER_ACCOUNT_CHANNEL_PEER, or legacy {@code |t:<key>})
     * and matches the user+agent pair. Returns {@code null} if no match.
     */
    private SessionEntry findSessionByConversationId(String agentId, String key, String userId) {
        if (key == null || key.isBlank()) return null;
        String gatewayAgentId = catalogService.peekGatewayAgentId(userId, agentId);
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(userId, e.userId())) continue;
            if (!sessionMatchesAgent(e, gatewayAgentId)) continue;
            if (key.equals(extractConversationId(e.gateKey()))) {
                return e;
            }
        }
        return null;
    }

    /**
     * Authorizes a session against the URL agent. {@link SessionEntry#agentId()} holds the
     * HarnessAgent's internal UUID (not the gateway/catalog id), so we cannot match by agent id
     * directly. Instead we look at the session's {@code gateKey} (which is deterministically
     * derived from {@code (userId, gatewayAgentId, conversationId)}) and check that it carries the
     * expected gatewayAgentId in its {@code |x:agentId=...} segment — independent of the
     * {@code |g:<conversationId>} segment that distinguishes ChatGPT-style sessions for the same
     * agent. Sub/group sessions that lack a gateKey fall through to a userId-only ownership check.
     */
    private static boolean sessionMatchesAgent(SessionEntry e, String gatewayAgentId) {
        if (gatewayAgentId == null) return false;
        String gateKey = e.gateKey();
        if (e.kind() == SessionKind.MAIN) {
            return gateKey != null && extractGatewayAgentId(gateKey).equals(gatewayAgentId);
        }
        // For sub/group sessions, gateKey may be unset; userId match upstream is sufficient.
        return gateKey == null || extractGatewayAgentId(gateKey).equals(gatewayAgentId);
    }

    /**
     * Extracts the {@code agentId} value from a canonical gateKey segment of the form
     * {@code |x:agentId=<value>}. Returns an empty string if no such segment is present.
     */
    private static String extractGatewayAgentId(String gateKey) {
        String needle = "|x:agentId=";
        int i = gateKey.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        return end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
    }

    /**
     * Extracts the conversationId from a canonical gateKey. With {@code
     * DmScope.PER_ACCOUNT_CHANNEL_PEER} the conversationId lives in the {@code |g:<value>} segment
     * (group field). Legacy gateKeys used {@code |t:<value>} (threadId field). Returns {@code null}
     * when neither segment is present.
     */
    static String extractConversationId(String gateKey) {
        if (gateKey == null) return null;
        String val = extractSegment(gateKey, "|g:");
        if (val == null) {
            val = extractSegment(gateKey, "|t:");
        }
        return val;
    }

    private static String extractSegment(String gateKey, String prefix) {
        int i = gateKey.indexOf(prefix);
        if (i < 0) return null;
        int start = i + prefix.length();
        int end = gateKey.indexOf('|', start);
        String val = end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
        return val.isEmpty() ? null : val;
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InboxEntry(
            String sessionKey,
            String sessionId,
            String agentId,
            String conversationId,
            String title,
            String label,
            long lastActivityMs,
            String lastMessage,
            boolean unread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResetResult(String sessionKey, boolean reset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadStateResult(String sessionKey, long readAtMs, boolean unread) {}
}
