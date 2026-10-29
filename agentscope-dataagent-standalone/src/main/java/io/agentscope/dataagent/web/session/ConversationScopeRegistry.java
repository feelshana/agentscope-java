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

import io.agentscope.dataagent.web.persistence.jpa.ConversationDatasetScopeEntity;
import io.agentscope.dataagent.web.persistence.jpa.ConversationDatasetScopeRepository;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Persists the last accepted selection for history restoration, immutable per-message snapshots,
 * and the active runtime scope used by tools that only receive a session id. Runtime writes are
 * serialized by the gateway turn gate; ordinary conversations may change selection between turns.
 */
@Component
public class ConversationScopeRegistry {

    private final ConcurrentHashMap<String, Selection> scopes = new ConcurrentHashMap<>();
    private final Object[] locks =
            java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public record Selection(
            List<String> groupIds, boolean allGroups, java.util.Map<String, String> groupNames) {
        public Selection {
            groupIds = groupIds.stream().distinct().sorted().toList();
            groupNames = java.util.Map.copyOf(groupNames);
        }
    }

    public ConversationScopeRegistry() {}

    /**
     * Purpose of the latest modeling request per conversation (in memory, not persisted). A
     * confirmation resume keeps the purpose of the request that raised the card.
     */
    private final ConcurrentHashMap<String, String> modelingPurposes = new ConcurrentHashMap<>();

    public void putModelingPurpose(String userId, String sessionKey, String purpose) {
        if (userId == null || sessionKey == null || sessionKey.isBlank()) return;
        String key = ConversationDatasetScopeEntity.key(userId, sessionKey);
        if (purpose == null) modelingPurposes.remove(key);
        else modelingPurposes.put(key, purpose);
    }

    public String modelingPurpose(String userId, String sessionKey) {
        if (userId == null || sessionKey == null || sessionKey.isBlank()) return null;
        return modelingPurposes.get(ConversationDatasetScopeEntity.key(userId, sessionKey));
    }

    /**
     * spec097: the analysis choice of the latest query request per conversation (in memory). Every
     * query request replaces it, so a choice never leaks into the next turn.
     */
    private final ConcurrentHashMap<String, io.agentscope.dataagent.tools.data.AnalysisTurn>
            analysisTurns = new ConcurrentHashMap<>();

    public void putAnalysis(
            String userId,
            String sessionKey,
            io.agentscope.dataagent.tools.data.AnalysisTurn turn) {
        if (userId == null || sessionKey == null || sessionKey.isBlank()) return;
        String key = ConversationDatasetScopeEntity.key(userId, sessionKey);
        if (turn == null) analysisTurns.remove(key);
        else analysisTurns.put(key, turn);
    }

    public io.agentscope.dataagent.tools.data.AnalysisTurn analysis(
            String userId, String sessionKey) {
        if (userId == null || sessionKey == null || sessionKey.isBlank()) return null;
        return analysisTurns.get(ConversationDatasetScopeEntity.key(userId, sessionKey));
    }

    private ConversationDatasetScopeRepository repository;

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public void put(String userId, String sessionKey, List<String> groupIds) {
        if (groupIds != null)
            putSelection(userId, sessionKey, new Selection(groupIds, false, java.util.Map.of()));
    }

    public void putSelection(String userId, String sessionKey, Selection selection) {
        if (userId == null || sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        String key = ConversationDatasetScopeEntity.key(userId, sessionKey);
        synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
            try {
                if (repository != null) {
                    var row =
                            new ConversationDatasetScopeEntity(
                                    userId,
                                    sessionKey,
                                    mapper.writeValueAsString(selection.groupIds()));
                    row.setSelectionJson(mapper.writeValueAsString(selection));
                    repository.save(row);
                }
                // Per-message snapshots are read in one batch by the history projection; do not
                // cache all turns.
                if (!sessionKey.startsWith("turn:")) scopes.put(key, selection);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("保存会话知识库范围失败", e);
            }
        }
    }

    public List<String> get(String userId, String sessionKey) {
        Selection selection = getSelection(userId, sessionKey);
        return selection == null ? null : selection.groupIds();
    }

    public Selection getSelection(String userId, String sessionKey) {
        if (userId == null || sessionKey == null) return null;
        String key = ConversationDatasetScopeEntity.key(userId, sessionKey);
        Selection existing = scopes.get(key);
        if (existing != null || repository == null) return existing;
        return repository
                .findById(key)
                .map(
                        row -> {
                            try {
                                Selection snapshot = readSelection(row);
                                scopes.put(key, snapshot);
                                return snapshot;
                            } catch (java.io.IOException e) {
                                throw new IllegalStateException("读取会话知识库范围失败", e);
                            }
                        })
                .orElse(null);
    }

    private Selection readSelection(ConversationDatasetScopeEntity row) throws java.io.IOException {
        if (row.getSelectionJson() != null)
            return mapper.readValue(row.getSelectionJson(), Selection.class);
        return new Selection(
                mapper.readValue(
                        row.getGroupsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}),
                false,
                java.util.Map.of());
    }

    /** Batch enrichment avoids a database query for every historical message. Legacy turns stay unlabeled. */
    public List<SessionTurnParser.TurnEntry> enrichTurns(
            String userId, List<SessionTurnParser.TurnEntry> turns) {
        if (repository == null || userId == null) return turns;
        var keys =
                turns.stream()
                        .filter(t -> "USER".equalsIgnoreCase(t.role()) && t.id() != null)
                        .map(t -> ConversationDatasetScopeEntity.key(userId, "turn:" + t.id()))
                        .toList();
        if (keys.isEmpty()) return turns;
        var byKey = new java.util.HashMap<String, Selection>();
        for (var row : repository.findAllById(keys)) {
            try {
                byKey.put(row.getId(), readSelection(row));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取历史问答知识库范围失败", e);
            }
        }
        return turns.stream()
                .map(
                        t -> {
                            var scope =
                                    byKey.get(
                                            ConversationDatasetScopeEntity.key(
                                                    userId, "turn:" + t.id()));
                            return scope == null
                                    ? t
                                    : new SessionTurnParser.TurnEntry(
                                            t.id(),
                                            t.parentId(),
                                            t.role(),
                                            t.content(),
                                            t.timestampMs(),
                                            t.toolName(),
                                            t.toolInput(),
                                            t.toolResult(),
                                            t.toolCallId(),
                                            scope);
                        })
                .toList();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationScopeRegistry(ConversationDatasetScopeRepository repository) {
        this();
        this.repository = repository;
    }
}
