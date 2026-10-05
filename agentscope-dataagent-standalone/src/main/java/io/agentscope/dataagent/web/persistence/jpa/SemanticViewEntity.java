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

/**
 * A named SQL view (specs/011 M2) compiled into the group's MDL on publish — the escape hatch for
 * calibers that a cube cannot express (HAVING thresholds, window functions, multi-join CTEs),
 * mirroring WrenAI's {@code views/<name>/} artifact. {@code sqlText} must be a single
 * SELECT/WITH statement; it is expanded by the wren engine at query time, never materialized.
 */
@Entity
@Table(
        name = "dataagent_semantic_view",
        indexes = {
            @Index(name = "ix_dataagent_view_group", columnList = "group_id"),
            @Index(
                    name = "ix_dataagent_view_group_name",
                    columnList = "group_id,name",
                    unique = true)
        })
public class SemanticViewEntity {

    @Id
    @Column(name = "view_id", length = 64, nullable = false)
    private String id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /**
     * The dataset this view is anchored to, if any — informational only (shown in the modeling
     * page and overview); the SQL itself may join any tables of the knowledge base.
     */
    @Column(name = "base_dataset_id", length = 64)
    private String baseDatasetId;

    @Column(name = "sql_text", length = 4000, nullable = false)
    private String sqlText;

    @Column(name = "description", length = 1000)
    private String description;

    /** DRAFT | PUBLISHED — views enter the MDL only when published. */
    @Column(name = "status", length = 16)
    private String status = "DRAFT";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public SemanticViewEntity() {}

    public SemanticViewEntity(
            String id,
            String groupId,
            String name,
            String baseDatasetId,
            String sqlText,
            String description) {
        this.id = id;
        this.groupId = groupId;
        this.name = name;
        this.baseDatasetId = baseDatasetId;
        this.sqlText = sqlText;
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseDatasetId() {
        return baseDatasetId;
    }

    public void setBaseDatasetId(String baseDatasetId) {
        this.baseDatasetId = baseDatasetId;
    }

    public String getSqlText() {
        return sqlText;
    }

    public void setSqlText(String sqlText) {
        this.sqlText = sqlText;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
