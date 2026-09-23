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
package io.agentscope.dataagent.runtime.session;

import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import java.util.Collection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable session registry backed by the {@code session_registry} MySQL table. Mirrors OpenClaw's
 * {@code sessions.json} store that tracks session metadata across restarts.
 *
 * <p>Thread-safety and atomicity are delegated to JPA transactions. The {@link StoredEntry} record
 * is the public view type consumed by {@link SessionAgentManager}.
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final SessionRegistryRepository repository;

    /**
     * JSON-serializable subset of {@link SessionEntry} for the public API. Kept as a record so
     * callers receive an immutable snapshot.
     */
    public record StoredEntry(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            String kind,
            String spawnedBy,
            int spawnDepth,
            long createdAtMs,
            long lastActivityMs,
            String sessionFilePath,
            String spawnRunId,
            String gateKey,
            String userId) {

        public static StoredEntry from(SessionEntry e) {
            return new StoredEntry(
                    e.sessionKey(),
                    e.agentId(),
                    e.sessionId(),
                    e.label(),
                    e.kind().getValue(),
                    e.spawnedBy(),
                    e.spawnDepth(),
                    e.createdAtMs(),
                    e.lastActivityMs(),
                    e.sessionFilePath(),
                    e.spawnRunId(),
                    e.gateKey(),
                    e.userId());
        }

        public static StoredEntry fromEntity(SessionRegistryEntity e) {
            return new StoredEntry(
                    e.getSessionKey(),
                    e.getAgentId(),
                    e.getSessionId(),
                    e.getLabel(),
                    e.getKind(),
                    e.getSpawnedBy(),
                    e.getSpawnDepth(),
                    e.getCreatedAtMs(),
                    e.getLastActivityMs(),
                    e.getSessionFilePath(),
                    e.getSpawnRunId(),
                    e.getGateKey(),
                    e.getUserId());
        }

        public SessionEntry toSessionEntry() {
            SessionKind sk = "main".equals(kind) ? SessionKind.MAIN : SessionKind.SUBAGENT;
            return new SessionEntry(
                    sessionKey,
                    agentId,
                    sessionId,
                    label,
                    sk,
                    spawnedBy,
                    spawnDepth,
                    createdAtMs,
                    lastActivityMs,
                    sessionFilePath,
                    spawnRunId,
                    gateKey,
                    userId);
        }

        public SessionRegistryEntity toEntity() {
            SessionRegistryEntity e = new SessionRegistryEntity();
            e.setSessionKey(sessionKey);
            e.setAgentId(agentId);
            e.setSessionId(sessionId);
            e.setLabel(label);
            e.setKind(kind);
            e.setSpawnedBy(spawnedBy);
            e.setSpawnDepth(spawnDepth);
            e.setCreatedAtMs(createdAtMs);
            e.setLastActivityMs(lastActivityMs);
            e.setSessionFilePath(sessionFilePath);
            e.setSpawnRunId(spawnRunId);
            e.setGateKey(gateKey);
            e.setUserId(userId);
            return e;
        }
    }

    public SessionStore(SessionRegistryRepository repository) {
        this.repository = repository;
    }

    /** Loads all entries. Logs a summary; no-op for the JPA backend (data lives in MySQL). */
    public void load() {
        long count = repository.count();
        log.info("Session store backed by JPA ({} existing entries)", count);
    }

    /** Persists a single session entry (upsert). */
    @Transactional
    public void save(SessionEntry entry) {
        repository.save(StoredEntry.from(entry).toEntity());
    }

    /** Updates only the {@code lastActivityMs} for the given key without a full entry replace. */
    @Transactional
    public void touch(String sessionKey, long lastActivityMs) {
        repository
                .findById(sessionKey)
                .ifPresent(
                        existing -> {
                            existing.setLastActivityMs(lastActivityMs);
                            repository.save(existing);
                        });
    }

    /** Removes a session entry by key. */
    @Transactional
    public void remove(String sessionKey) {
        repository.deleteById(sessionKey);
    }

    /** Returns a snapshot of all stored entries. */
    public Collection<StoredEntry> listAll() {
        return repository.findAll().stream().map(StoredEntry::fromEntity).toList();
    }

    /** Returns a single entry by key, if present. */
    public Optional<StoredEntry> get(String sessionKey) {
        return repository.findById(sessionKey).map(StoredEntry::fromEntity);
    }

    public int size() {
        return (int) repository.count();
    }
}
