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
 * A knowledge base (KB): a named, per-user container grouping uploaded datasets and one
 * relationship document. Mirrors the TC DataAgent "知识库" concept so users organise tables and
 * docs under a shared business context instead of a flat list.
 */
@Entity
@Table(
        name = "dataagent_dataset_group",
        indexes = {
            @Index(name = "ix_dataagent_group_owner", columnList = "owner_id"),
            @Index(
                    name = "ix_dataagent_group_owner_name",
                    columnList = "owner_id,name",
                    unique = true)
        })
public class DatasetGroupEntity {

    @Id
    @Column(name = "group_id", length = 64, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    /** NONE | INITIALIZING | FAILED | DIRTY | PUBLISHED — MDL lifecycle state. */
    @Column(name = "mdl_state", length = 16)
    private String mdlState = "NONE";

    /** User-facing summary of the most recent MDL build failure; cleared after publish. */
    @Column(name = "mdl_last_error", length = 2000)
    private String mdlLastError;

    /** Monotonic MDL version; bumped on each publish. */
    @Column(name = "mdl_version")
    private int mdlVersion = 0;

    /** When the group's MDL was last published; null while never published. */
    @Column(name = "mdl_published_at")
    private Instant mdlPublishedAt;

    public DatasetGroupEntity() {}

    public DatasetGroupEntity(String id, String ownerId, String name, String description) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
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

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public String getMdlState() {
        return mdlState;
    }

    public void setMdlState(String mdlState) {
        this.mdlState = mdlState;
    }

    public String getMdlLastError() {
        return mdlLastError;
    }

    public void setMdlLastError(String mdlLastError) {
        this.mdlLastError = mdlLastError;
    }

    public int getMdlVersion() {
        return mdlVersion;
    }

    public void setMdlVersion(int mdlVersion) {
        this.mdlVersion = mdlVersion;
    }

    public Instant getMdlPublishedAt() {
        return mdlPublishedAt;
    }

    public void setMdlPublishedAt(Instant mdlPublishedAt) {
        this.mdlPublishedAt = mdlPublishedAt;
    }
}
