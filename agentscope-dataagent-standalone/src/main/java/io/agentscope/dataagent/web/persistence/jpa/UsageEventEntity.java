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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A single usage event (one per user turn), persisted to MySQL. Replaces the former in-memory
 * {@code CopyOnWriteArrayList} store.
 */
@Entity
@Table(
        name = "usage_event",
        indexes = {
            @Index(name = "ix_usage_event_ts", columnList = "timestamp_ms"),
            @Index(name = "ix_usage_event_user_ts", columnList = "user_id, timestamp_ms"),
            @Index(name = "ix_usage_event_agent_ts", columnList = "agent_id, timestamp_ms")
        })
public class UsageEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp_ms", nullable = false)
    private long timestampMs;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    public UsageEventEntity() {}

    public UsageEventEntity(long timestampMs, String userId, String agentId, long durationMs) {
        this.timestampMs = timestampMs;
        this.userId = userId;
        this.agentId = agentId;
        this.durationMs = durationMs;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
