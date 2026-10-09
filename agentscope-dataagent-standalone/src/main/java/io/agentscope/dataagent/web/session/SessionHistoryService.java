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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionMessageEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionMessageRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable display history projected from the engine's append-only local transcript.
 * The engine's own context/checkpoint remains intact for resuming turns.
 * HTTP inbox requests never scan files or contact Docker.
 */
@Service
public class SessionHistoryService {
    private static final Logger log = LoggerFactory.getLogger(SessionHistoryService.class);
    private final DataAgentBootstrap bootstrap;
    private final SessionRegistryRepository registry;
    private final SessionHistoryRepository histories;
    private final SessionMessageRepository messages;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "session-history-index");
                        t.setDaemon(true);
                        return t;
                    });
    private int backfillPage;
    private final Object[] locks =
            java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    private Object lock(String key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }

    public SessionHistoryService(
            DataAgentBootstrap bootstrap,
            SessionRegistryRepository registry,
            SessionHistoryRepository histories,
            SessionMessageRepository messages,
            PlatformTransactionManager transactionManager) {
        this.bootstrap = bootstrap;
        this.registry = registry;
        this.histories = histories;
        this.messages = messages;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        bootstrap.gateway().setHistoryObserver(dirty::add);
        bootstrap.gateway().setHistoryFlusher(this::refresh);
        worker.scheduleWithFixedDelay(this::indexBatch, 0, 1, TimeUnit.SECONDS);
    }

    private void indexBatch() {
        try {
            // Bounded legacy backfill / crash recovery. Stat files; parse only when changed.
            var page =
                    registry.findAll(
                            PageRequest.of(
                                    backfillPage,
                                    20,
                                    org.springframework.data.domain.Sort.by("sessionKey")));
            for (var s : page) dirty.add(s.getSessionKey());
            backfillPage = page.hasNext() ? backfillPage + 1 : 0;
            int count = 0;
            for (String key : dirty) {
                if (count++ >= 40) break;
                dirty.remove(key);
                try {
                    refresh(key);
                } catch (Exception e) {
                    log.warn("History indexing failed for {}", key, e);
                }
            }
        } catch (Exception e) {
            log.warn("History indexing batch failed", e);
        }
    }

    /** Serialized with deletion/reset so indexing cannot resurrect a removed transcript. */
    public void refresh(String key) {
        synchronized (lock(key)) {
            refreshLocked(key);
        }
    }

    private void refreshLocked(String key) {
        var source = registry.findById(key).orElse(null);
        if (source == null) return;
        SessionEntry entry =
                io.agentscope.dataagent.runtime.session.SessionStore.StoredEntry.fromEntity(source)
                        .toSessionEntry();
        Path path = transcript(entry);
        if (path == null)
            return; // A lost runtime file must never erase already saved display history.
        try {
            long stamp = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            var previous = histories.findById(key).orElse(null);
            if (previous != null
                    && Objects.equals(previous.sessionId, entry.sessionId())
                    && previous.fileStamp == stamp
                    && previous.fileSize == size
                    && path.toString().equals(previous.sourcePath)) return;
            if (previous != null
                    && Objects.equals(previous.sessionId, entry.sessionId())
                    && previous.sourcePath != null
                    && previous.sourcePath.endsWith(".log.jsonl")
                    && !path.toString().endsWith(".log.jsonl")) return;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            var turns = SessionTurnParser.parse(content);
            // A concurrent append or partial last line is retried by the next batch.
            if (Files.size(path) != size || Files.getLastModifiedTime(path).toMillis() != stamp)
                return;
            tx.executeWithoutResult(
                    status -> {
                        if (!registry.existsById(key)) return;
                        SessionHistoryEntity h =
                                previous != null ? previous : new SessionHistoryEntity();
                        if (previous != null
                                && (!Objects.equals(previous.sessionId, entry.sessionId())
                                        || (previous.sourcePath != null
                                                && !previous.sourcePath.endsWith(".log.jsonl")
                                                && path.toString().endsWith(".log.jsonl")))) {
                            messages.deleteBySessionKey(key);
                            messages.flush();
                            h.title = null;
                            h.preview = null;
                            h.messageCount = 0;
                        }
                        var old = messages.findBySessionKeyOrderByPositionAsc(key);
                        Map<String, SessionMessageEntity> byId = new HashMap<>();
                        int nextPosition = 0;
                        for (var m : old) {
                            byId.put(m.id, m);
                            nextPosition = Math.max(nextPosition, m.position + 1);
                        }
                        List<SessionMessageEntity> changed = new ArrayList<>();
                        long activity = entry.lastActivityMs();
                        for (int i = 0; i < turns.size(); i++) {
                            var turn = turns.get(i);
                            activity = Math.max(activity, turn.timestampMs());
                            String payload;
                            try {
                                payload = mapper.writeValueAsString(turn);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                            // Stable identities keep compaction or a shorter context fallback from
                            // overwriting earlier display messages at the same ordinal.
                            String kind =
                                    turn.toolResult() != null
                                            ? "result"
                                            : turn.toolInput() != null ? "call" : turn.role();
                            String identity =
                                    turn.id() != null
                                            ? turn.id()
                                            : "legacy:" + i + ":" + turn.timestampMs();
                            String id =
                                    UUID.nameUUIDFromBytes(
                                                    (key
                                                                    + "\n"
                                                                    + entry.sessionId()
                                                                    + "\n"
                                                                    + kind
                                                                    + "\n"
                                                                    + identity)
                                                            .getBytes(StandardCharsets.UTF_8))
                                            .toString();
                            var m = byId.get(id);
                            if (m != null && payload.equals(m.payload)) continue;
                            if (m == null) {
                                m = new SessionMessageEntity();
                                m.id = id;
                                m.sessionKey = key;
                                m.position = nextPosition++;
                            }
                            m.payload = payload;
                            byId.put(id, m);
                            changed.add(m);
                        }
                        messages.saveAll(changed);
                        int titlePosition = Integer.MAX_VALUE;
                        int previewPosition = -1;
                        for (var m : byId.values()) {
                            SessionTurnParser.TurnEntry turn;
                            try {
                                turn =
                                        mapper.readValue(
                                                m.payload, SessionTurnParser.TurnEntry.class);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                            if (turn.content() == null || turn.content().isBlank()) continue;
                            if ("USER".equalsIgnoreCase(turn.role())
                                    && m.position < titlePosition) {
                                h.title = shorten(turn.content().replaceAll("\\s+", " "), 40);
                                titlePosition = m.position;
                            }
                            if (m.position > previewPosition) {
                                h.preview = shorten(turn.content(), 200);
                                previewPosition = m.position;
                            }
                        }
                        h.sessionKey = key;
                        h.sessionId = entry.sessionId();
                        h.activityMs = Math.max(h.activityMs, activity);
                        h.fileStamp = stamp;
                        h.fileSize = size;
                        h.sourcePath = path.toString();
                        h.messageCount = nextPosition;
                        histories.save(h);
                    });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read local transcript", e);
        }
    }

    public Path transcript(SessionEntry entry) {
        // Resolve through the correct user namespace, without accessing the sandbox filesystem.
        String gateway = segment(entry.gateKey(), "|x:agentId=");
        var agent = gateway == null ? null : bootstrap.gateway().findAgent(gateway);
        if (agent != null && agent.getWorkspaceManager() != null) {
            RuntimeContext rc = RuntimeContext.builder().userId(entry.userId()).build();
            var wm = agent.getWorkspaceManager();
            Path log = wm.resolveSessionLogFile(rc, agent.getName(), entry.sessionId());
            if (Files.isRegularFile(log)) return log;
            Path ctx = wm.resolveSessionContextFile(rc, agent.getName(), entry.sessionId());
            if (Files.isRegularFile(ctx)) return ctx;
        }
        if (entry.sessionFilePath() == null) return null;
        Path file = Path.of(entry.sessionFilePath());
        if (Files.isRegularFile(file)) return file;
        return null;
    }

    public record MessagePage(
            List<SessionTurnParser.TurnEntry> items, Integer nextBefore, boolean hasMore) {}

    public MessagePage page(String key, Integer before, int limit) {
        int take = Math.max(1, Math.min(limit, 100));
        var rows =
                messages.findBySessionKeyAndPositionLessThanOrderByPositionDesc(
                        key,
                        before == null ? Integer.MAX_VALUE : before,
                        PageRequest.of(0, take + 1));
        boolean more = rows.size() > take;
        if (more) rows = new ArrayList<>(rows.subList(0, take));
        Integer next = rows.isEmpty() ? null : rows.get(rows.size() - 1).position;
        List<SessionTurnParser.TurnEntry> result = new ArrayList<>();
        for (var row : rows) {
            try {
                result.add(mapper.readValue(row.payload, SessionTurnParser.TurnEntry.class));
            } catch (Exception e) {
                throw new IllegalStateException("Invalid stored message", e);
            }
        }
        Collections.reverse(result);
        return new MessagePage(result, more ? next : null, more);
    }

    public List<SessionTurnParser.TurnEntry> all(String key) {
        List<SessionTurnParser.TurnEntry> result = new ArrayList<>();
        for (var row : messages.findBySessionKeyOrderByPositionAsc(key)) {
            try {
                result.add(mapper.readValue(row.payload, SessionTurnParser.TurnEntry.class));
            } catch (Exception e) {
                throw new IllegalStateException("Invalid stored message", e);
            }
        }
        return result;
    }

    public void clear(String key, Runnable removeRuntime) {
        synchronized (lock(key)) {
            clearLocked(key, removeRuntime);
        }
    }

    private void clearLocked(String key, Runnable removeRuntime) {
        removeRuntime.run();
        tx.executeWithoutResult(
                status -> {
                    messages.deleteBySessionKey(key);
                    histories.deleteById(key);
                });
        dirty.remove(key);
    }

    public static String segment(String value, String prefix) {
        if (value == null) return null;
        int start = value.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = value.indexOf('|', start);
        return value.substring(start, end < 0 ? value.length() : end);
    }

    private static String shorten(String value, int max) {
        value = value.strip();
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    @PreDestroy
    public void stop() {
        worker.shutdownNow();
    }
}
