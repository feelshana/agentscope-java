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
 * A semantic cube (metrics view) defined on top of one base table of a knowledge base, edited in
 * the group's semantic-modeling tab and compiled into the group's MDL on publish (specs/010 M1).
 * Measures / dimensions / timeDimensions are JSON arrays whose object shape ({@code name},
 * {@code column}/{@code expression}, {@code type}, {@code description}) is pinned by the modeling
 * API, not by this entity.
 */
@Entity
@Table(
        name = "dataagent_semantic_cube",
        indexes = {
            @Index(name = "ix_dataagent_cube_group", columnList = "group_id"),
            @Index(
                    name = "ix_dataagent_cube_group_name",
                    columnList = "group_id,name",
                    unique = true)
        })
public class SemanticCubeEntity {

    @Id
    @Column(name = "cube_id", length = 64, nullable = false)
    private String id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /** The dataset (base object) this cube aggregates; references a dataset id. */
    @Column(name = "base_dataset_id", length = 64, nullable = false)
    private String baseDatasetId;

    @Column(name = "measures_json", length = 4000)
    private String measuresJson = "[]";

    @Column(name = "dimensions_json", length = 4000)
    private String dimensionsJson = "[]";

    @Column(name = "time_dimensions_json", length = 2000)
    private String timeDimensionsJson = "[]";

    @Column(name = "description", length = 1000)
    private String description;

    /** DRAFT | PUBLISHED — cubes enter the MDL only when published (specs/010 M2). */
    @Column(name = "status", length = 16)
    private String status = "DRAFT";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public SemanticCubeEntity() {}

    public SemanticCubeEntity(
            String id, String groupId, String name, String baseDatasetId, String description) {
        this.id = id;
        this.groupId = groupId;
        this.name = name;
        this.baseDatasetId = baseDatasetId;
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

    public String getMeasuresJson() {
        return measuresJson;
    }

    public void setMeasuresJson(String measuresJson) {
        this.measuresJson = measuresJson;
    }

    public String getDimensionsJson() {
        return dimensionsJson;
    }

    public void setDimensionsJson(String dimensionsJson) {
        this.dimensionsJson = dimensionsJson;
    }

    public String getTimeDimensionsJson() {
        return timeDimensionsJson;
    }

    public void setTimeDimensionsJson(String timeDimensionsJson) {
        this.timeDimensionsJson = timeDimensionsJson;
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
