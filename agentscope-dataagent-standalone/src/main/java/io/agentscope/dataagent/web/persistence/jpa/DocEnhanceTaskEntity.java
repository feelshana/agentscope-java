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
import java.time.Instant;

/** One asynchronous document-to-semantic proposal analysis run for a tenant-owned group. */
@Entity
@Table(
        name = "dataagent_doc_enhance_task",
        indexes = {
            @Index(name = "ix_doc_enhance_task_owner_group", columnList = "owner_id,group_id"),
            @Index(name = "ix_doc_enhance_task_group_status", columnList = "group_id,status")
        })
public class DocEnhanceTaskEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "task_id", length = 64, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "source_type", length = 16, nullable = false)
    private String sourceType;

    @Column(name = "source_label", length = 255)
    private String sourceLabel;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_RUNNING;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    public DocEnhanceTaskEntity() {}

    public DocEnhanceTaskEntity(
            String id, String ownerId, String groupId, String sourceType, String sourceLabel) {
        this.id = id;
        this.ownerId = ownerId;
        this.groupId = groupId;
        this.sourceType = sourceType;
        this.sourceLabel = sourceLabel;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
