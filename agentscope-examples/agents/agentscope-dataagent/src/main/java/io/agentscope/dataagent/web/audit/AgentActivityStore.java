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
package io.agentscope.dataagent.web.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.persistence.jpa.ActivityEventEntity;
import io.agentscope.dataagent.web.persistence.jpa.ActivityEventRepository;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-agent append-only activity log backed by the {@code activity_event} MySQL table. Replaces
 * the former JSONL file store ({@code activity.jsonl}) that lived inside each agent's namespaced
 * workspace.
 *
 * <p>Writes are transactional appends; reads are ordered newest-first with an optional
 * {@code sinceMs} filter. The {@code MAX_RETURN} cap protects the UI from log explosions.
 */
@Service
public class AgentActivityStore {

    private static final Logger log = LoggerFactory.getLogger(AgentActivityStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    /** Hard cap on a single read response so a log explosion can't blow up the UI. */
    static final int MAX_RETURN = 500;

    private final ActivityEventRepository repository;
    private final UserStore userStore;

    public AgentActivityStore(ActivityEventRepository repository, UserStore userStore) {
        this.repository = repository;
        this.userStore = userStore;
    }

    /**
     * Appends a single event to {@code (ownerId, agentId)}'s log. Best-effort: persistence errors
     * are logged but never propagated, so a failing audit log cannot break the user-facing call.
     */
    public void record(String ownerId, String agentId, ActorRef actor, String action) {
        record(ownerId, agentId, actor, action, null, null);
    }

    /**
     * Appends a single event with an optional target and metadata. See
     * {@link #record(String, String, ActorRef, String)} for failure semantics.
     */
    @Transactional
    public void record(
            String ownerId,
            String agentId,
            ActorRef actor,
            String action,
            String target,
            Map<String, Object> metadata) {
        if (ownerId == null || agentId == null || action == null) {
            return;
        }
        ActivityEvent event =
                new ActivityEvent(
                        newId(),
                        System.currentTimeMillis(),
                        actor != null ? actor.userId() : null,
                        actor != null ? actor.username() : null,
                        action,
                        target,
                        metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata));
        try {
            repository.save(toEntity(ownerId, agentId, event));
        } catch (RuntimeException ex) {
            log.warn("Activity log write failed for {}/{}: {}", ownerId, agentId, ex.getMessage());
        }
    }

    /**
     * Returns the {@code limit} most-recent events for {@code (ownerId, agentId)}, optionally
     * filtered to events strictly newer than {@code sinceMs}. Returned newest-first.
     */
    public List<ActivityEvent> list(String ownerId, String agentId, Long sinceMs, int limit) {
        int cap = Math.min(limit > 0 ? limit : 50, MAX_RETURN);
        try {
            List<ActivityEventEntity> entities;
            if (sinceMs != null) {
                entities =
                        repository
                                .findByOwnerIdAndAgentIdAndTimestampMsGreaterThanOrderByTimestampMsDesc(
                                        ownerId, agentId, sinceMs, PageRequest.of(0, cap));
            } else {
                entities =
                        repository.findByOwnerIdAndAgentIdOrderByTimestampMsDesc(
                                ownerId, agentId, PageRequest.of(0, cap));
            }
            return entities.stream().map(this::fromEntity).toList();
        } catch (RuntimeException ex) {
            log.warn("Activity log read failed for {}/{}: {}", ownerId, agentId, ex.getMessage());
            return List.of();
        }
    }

    /** Resolve a user's display name once; tolerant of unknown ids (returns the id). */
    public ActorRef actor(String userId) {
        if (userId == null) return new ActorRef(null, null);
        Optional<UserStore.UserRecord> rec = userStore.findById(userId);
        return new ActorRef(userId, rec.map(UserStore.UserRecord::username).orElse(userId));
    }

    // -----------------------------------------------------------------
    //  Mapping helpers
    // -----------------------------------------------------------------

    private ActivityEventEntity toEntity(String ownerId, String agentId, ActivityEvent event) {
        ActivityEventEntity e = new ActivityEventEntity();
        e.setEventId(event.id());
        e.setOwnerId(ownerId);
        e.setAgentId(agentId);
        e.setTimestampMs(event.timestampMs());
        e.setActorUserId(event.actorUserId());
        e.setActorUsername(event.actorUsername());
        e.setAction(event.action());
        e.setTarget(event.target());
        if (event.metadata() != null) {
            try {
                e.setMetadataJson(MAPPER.writeValueAsString(event.metadata()));
            } catch (JsonProcessingException ex) {
                log.warn("Failed to serialize activity metadata: {}", ex.getMessage());
            }
        }
        return e;
    }

    private ActivityEvent fromEntity(ActivityEventEntity e) {
        Map<String, Object> metadata = null;
        if (e.getMetadataJson() != null && !e.getMetadataJson().isBlank()) {
            try {
                metadata = MAPPER.readValue(e.getMetadataJson(), MAP_TYPE);
            } catch (JsonProcessingException ex) {
                log.warn("Failed to deserialize activity metadata: {}", ex.getMessage());
            }
        }
        return new ActivityEvent(
                e.getEventId(),
                e.getTimestampMs(),
                e.getActorUserId(),
                e.getActorUsername(),
                e.getAction(),
                e.getTarget(),
                metadata);
    }

    private static String newId() {
        byte[] buf = new byte[12];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** Identifying tuple for the actor who triggered an event. Both fields may be {@code null}. */
    public record ActorRef(String userId, String username) {}
}
