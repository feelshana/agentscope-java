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
package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Persistent representation of a session registry entry. Mirrors the former {@code sessions.json}
 * file store. Keyed by {@code sessionKey} (same natural key used on disk).
 */
@Entity
@Table(
        name = "session_registry",
        indexes = {
            @Index(name = "ix_session_registry_agent_id", columnList = "agent_id"),
            @Index(name = "ix_session_registry_user_id", columnList = "user_id")
        })
public class SessionRegistryEntity {

    @Id
    @Column(name = "session_key", length = 255, nullable = false)
    private String sessionKey;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "label", length = 500)
    private String label;

    @Column(name = "kind", length = 32)
    private String kind;

    @Column(name = "spawned_by", length = 255)
    private String spawnedBy;

    @Column(name = "spawn_depth")
    private int spawnDepth;

    @Column(name = "created_at_ms")
    private long createdAtMs;

    @Column(name = "last_activity_ms")
    private long lastActivityMs;

    @Column(name = "session_file_path", length = 1024)
    private String sessionFilePath;

    @Column(name = "spawn_run_id", length = 255)
    private String spawnRunId;

    @Column(name = "gate_key", length = 255)
    private String gateKey;

    @Column(name = "user_id", length = 128)
    private String userId;

    public SessionRegistryEntity() {}

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSpawnedBy() {
        return spawnedBy;
    }

    public void setSpawnedBy(String spawnedBy) {
        this.spawnedBy = spawnedBy;
    }

    public int getSpawnDepth() {
        return spawnDepth;
    }

    public void setSpawnDepth(int spawnDepth) {
        this.spawnDepth = spawnDepth;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public void setCreatedAtMs(long createdAtMs) {
        this.createdAtMs = createdAtMs;
    }

    public long getLastActivityMs() {
        return lastActivityMs;
    }

    public void setLastActivityMs(long lastActivityMs) {
        this.lastActivityMs = lastActivityMs;
    }

    public String getSessionFilePath() {
        return sessionFilePath;
    }

    public void setSessionFilePath(String sessionFilePath) {
        this.sessionFilePath = sessionFilePath;
    }

    public String getSpawnRunId() {
        return spawnRunId;
    }

    public void setSpawnRunId(String spawnRunId) {
        this.spawnRunId = spawnRunId;
    }

    public String getGateKey() {
        return gateKey;
    }

    public void setGateKey(String gateKey) {
        this.gateKey = gateKey;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
