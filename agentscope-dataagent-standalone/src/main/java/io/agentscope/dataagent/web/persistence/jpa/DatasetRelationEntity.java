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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A structured relation edge between two datasets inside a knowledge base: which columns join
 * them and how the edge was derived (column-name heuristics or the user's relationship document).
 * Powers the graph tab and the agent's find_related_tables tool. The {@code status}/{@code
 * joinType} fields carry the human-review verdict consumed by the semantic-modeling flow
 * (specs/010): curated records survive {@code RelationInferenceService#reinferGroup} rebuilds.
 */
@Entity
@Table(name = "dataagent_dataset_relation")
public class DatasetRelationEntity {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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

    /**
     * Composite-key extensions (specs/013): JSON array texts like {@code ["province","stat_date"]}.
     * Nullable String (never primitive) so legacy rows with NULL load cleanly; the single-column
     * fields above always carry the first column of the pair.
     */
    @Column(name = "source_columns", length = 512)
    private String sourceColumns;

    @Column(name = "target_columns", length = 512)
    private String targetColumns;

    /** SAME_COLUMN | SUFFIX | DOC | LLM */
    @Column(name = "relation_type", length = 32, nullable = false)
    private String relationType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    /** inferred | doc | llm | manual */
    @Column(name = "origin", length = 16, nullable = false)
    private String origin = "inferred";

    /** MANY_TO_ONE | ONE_TO_MANY | ONE_TO_ONE; null until human review or probing fills it. */
    @Column(name = "join_type", length = 32)
    private String joinType;

    /** PENDING | CONFIRMED | REJECTED — review verdict (null on legacy rows = PENDING). */
    @Column(name = "status", length = 16)
    private String status = "PENDING";

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

    public String getSourceColumns() {
        return sourceColumns;
    }

    public void setSourceColumns(String sourceColumns) {
        this.sourceColumns = sourceColumns;
    }

    public String getTargetColumns() {
        return targetColumns;
    }

    public void setTargetColumns(String targetColumns) {
        this.targetColumns = targetColumns;
    }

    /** Column pair on the source side: JSON first, single-column fallback for legacy rows. */
    public List<String> sourceColumnList() {
        return columnList(sourceColumns, sourceColumn);
    }

    /** Column pair on the target side: JSON first, single-column fallback for legacy rows. */
    public List<String> targetColumnList() {
        return columnList(targetColumns, targetColumn);
    }

    /** Writes the composite pair: JSON column plus the first column into the legacy field. */
    public void setSourceColumnList(List<String> columns) {
        this.sourceColumns = toJson(columns);
        this.sourceColumn = columns == null || columns.isEmpty() ? null : columns.get(0);
    }

    /** Writes the composite pair: JSON column plus the first column into the legacy field. */
    public void setTargetColumnList(List<String> columns) {
        this.targetColumns = toJson(columns);
        this.targetColumn = columns == null || columns.isEmpty() ? null : columns.get(0);
    }

    private static List<String> columnList(String json, String single) {
        if (json != null && !json.isBlank()) {
            try {
                List<String> parsed =
                        JSON_MAPPER.readValue(json, new TypeReference<List<String>>() {});
                if (parsed != null && !parsed.isEmpty()) {
                    return List.copyOf(parsed);
                }
            } catch (JsonProcessingException | RuntimeException e) {
                // fall through to the single-column fallback
            }
        }
        List<String> out = new ArrayList<>();
        if (single != null && !single.isBlank()) {
            out.add(single);
        }
        return List.copyOf(out);
    }

    private static String toJson(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        try {
            return JSON_MAPPER.writeValueAsString(columns);
        } catch (JsonProcessingException e) {
            return null;
        }
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

    public String getJoinType() {
        return joinType;
    }

    public void setJoinType(String joinType) {
        this.joinType = joinType;
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
}
