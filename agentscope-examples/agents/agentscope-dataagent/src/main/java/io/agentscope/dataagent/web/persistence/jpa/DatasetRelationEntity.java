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
 * A structured relation edge between two datasets inside a knowledge base: which columns join
 * them and how the edge was derived (column-name heuristics or the user's relationship document).
 * Powers the graph tab and the agent's find_related_tables tool.
 */
@Entity
@Table(name = "dataagent_dataset_relation")
public class DatasetRelationEntity {

    @Id
    @Column(name = "relation_id", length = 64, nullable = false)
    private String id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "source_dataset_id", length = 64, nullable = false)
    private String sourceDatasetId;

    @Column(name = "source_column", length = 128)
    private String sourceColumn;

    @Column(name = "target_dataset_id", length = 64, nullable = false)
    private String targetDatasetId;

    @Column(name = "target_column", length = 128)
    private String targetColumn;

    /** SAME_COLUMN | SUFFIX | DOC | LLM */
    @Column(name = "relation_type", length = 32, nullable = false)
    private String relationType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    /** inferred | doc | llm */
    @Column(name = "origin", length = 16, nullable = false)
    private String origin = "inferred";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public DatasetRelationEntity() {}

    public DatasetRelationEntity(
            String id,
            String groupId,
            String sourceDatasetId,
            String sourceColumn,
            String targetDatasetId,
            String targetColumn,
            String relationType,
            String description,
            double confidence,
            String origin) {
        this.id = id;
        this.groupId = groupId;
        this.sourceDatasetId = sourceDatasetId;
        this.sourceColumn = sourceColumn;
        this.targetDatasetId = targetDatasetId;
        this.targetColumn = targetColumn;
        this.relationType = relationType;
        this.description = description;
        this.confidence = confidence;
        this.origin = origin;
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

    public String getSourceDatasetId() {
        return sourceDatasetId;
    }

    public void setSourceDatasetId(String sourceDatasetId) {
        this.sourceDatasetId = sourceDatasetId;
    }

    public String getSourceColumn() {
        return sourceColumn;
    }

    public void setSourceColumn(String sourceColumn) {
        this.sourceColumn = sourceColumn;
    }

    public String getTargetDatasetId() {
        return targetDatasetId;
    }

    public void setTargetDatasetId(String targetDatasetId) {
        this.targetDatasetId = targetDatasetId;
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = targetColumn;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
