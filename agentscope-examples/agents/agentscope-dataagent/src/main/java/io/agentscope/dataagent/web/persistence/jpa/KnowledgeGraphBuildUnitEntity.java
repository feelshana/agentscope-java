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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One extraction unit of a knowledge-graph build: either the group's knowledge document or a single
 * dataset's schema. Carries per-unit processing status so the TC-style overview panel can show
 * 已完成 / 解析中 / 解析失败 / 未处理 counts and a progress bar.
 */
@Entity
@Table(name = "dataagent_kg_build_unit")
public class KnowledgeGraphBuildUnitEntity {

    public static final String TYPE_KNOWLEDGE_DOC = "KNOWLEDGE_DOC";
    public static final String TYPE_DATASET_SCHEMA = "DATASET_SCHEMA";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "unit_id", length = 64, nullable = false)
    private String id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "unit_type", length = 32, nullable = false)
    private String unitType;

    /** The knowledge group id (for doc) or dataset id (for schema). */
    @Column(name = "unit_ref_id", length = 64)
    private String unitRefId;

    @Column(name = "unit_name", length = 256)
    private String unitName;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    /** For doc-chunk units: the chunk text to extract from. Null for schema units. */
    @Lob
    @Column(name = "payload")
    private String payload;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    public KnowledgeGraphBuildUnitEntity() {}

    public KnowledgeGraphBuildUnitEntity(
            String id, String groupId, String unitType, String unitRefId, String unitName) {
        this.id = id;
        this.groupId = groupId;
        this.unitType = unitType;
        this.unitRefId = unitRefId;
        this.unitName = unitName;
        this.status = STATUS_PENDING;
        this.createdAt = Instant.now();
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

    public String getUnitType() {
        return unitType;
    }

    public void setUnitType(String unitType) {
        this.unitType = unitType;
    }

    public String getUnitRefId() {
        return unitRefId;
    }

    public void setUnitRefId(String unitRefId) {
        this.unitRefId = unitRefId;
    }

    public String getUnitName() {
        return unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
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
