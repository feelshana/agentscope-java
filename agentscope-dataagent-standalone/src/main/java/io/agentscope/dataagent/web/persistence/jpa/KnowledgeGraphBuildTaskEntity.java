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
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One knowledge-graph build run for a knowledge base: lifecycle status plus trigger/finish times.
 * Per-document progress lives on {@link KnowledgeGraphBuildUnitEntity}; this row only tracks the
 * overall run so a second trigger while RUNNING can be rejected (409).
 */
@Entity
@Table(name = "dataagent_kg_build_task")
public class KnowledgeGraphBuildTaskEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    @Id
    @Column(name = "task_id", length = 64, nullable = false)
    private String id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "status", length = 24, nullable = false)
    private String status = STATUS_RUNNING;

    @Column(name = "triggered_at")
    private Instant triggeredAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    public KnowledgeGraphBuildTaskEntity() {}

    public KnowledgeGraphBuildTaskEntity(String id, String groupId, String ownerId) {
        this.id = id;
        this.groupId = groupId;
        this.ownerId = ownerId;
        this.status = STATUS_RUNNING;
        this.triggeredAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(Instant triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
