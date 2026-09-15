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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Metadata for a user-uploaded tabular dataset (Excel / CSV). The physical rows live in the
 * dedicated dataset MySQL store under {@code schemaName} (one schema per dataset, table {@code
 * tableName}); this entity only records what the platform needs to list, scope and re-register
 * the dataset as an agent-visible {@code DataSource} after a restart.
 *
 * <p>{@code (ownerId, groupId, name)} is unique so a user cannot shadow an existing dataset by re-uploading
 * under the same friendly name within the same knowledge base group.
 */
@Entity
@Table(
        name = "dataagent_dataset",
        indexes = {
            @Index(name = "ix_dataagent_dataset_owner", columnList = "owner_id"),
            @Index(
                    name = "ix_dataagent_dataset_owner_group_name",
                    columnList = "owner_id,group_id,name",
                    unique = true)
        })
public class DatasetEntity {

    @Id
    @Column(name = "dataset_id", length = 64, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    /** Knowledge-base group this dataset belongs to; null for pre-group legacy rows. */
    @Column(name = "group_id", length = 64)
    private String groupId;

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    @Column(name = "name", length = 191, nullable = false)
    private String name;

    /** Dedicated MySQL schema holding this dataset's table, e.g. {@code da_ds_a1b2c3d4}. */
    @Column(name = "schema_name", length = 128, nullable = false)
    private String schemaName;

    @Column(name = "table_name", length = 128, nullable = false)
    private String tableName;

    @Lob
    @Column(name = "description")
    private String description;

    /** JSON array of {@code {"name","sqlType","nullable"}} column descriptors. */
    @Lob
    @Column(name = "column_schema_json")
    private String columnSchemaJson;

    @Column(name = "row_count")
    private long rowCount;

    @Column(name = "source_file_name", length = 255)
    private String sourceFileName;

    /**
     * Origin of this dataset: 'upload' (file ingested into the dataset store),
     * 'datasource' (external table reference), or 'derived' (created by a manifest's derived SQL).
     */
    @Column(name = "origin", length = 16)
    private String origin = "upload";

    /** When origin='datasource', the ExternalDataSourceEntity id this table lives in. */
    @Column(name = "external_datasource_id", length = 64)
    private String externalDataSourceId;

    /**
     * When origin='derived', the SQL that created this table (for lineage / re-import idempotency).
     * Stored as raw text so the UI and the manifest re-import can display / reproduce it.
     */
    @Lob
    @Column(name = "source_sql")
    private String sourceSQL;

    public String getOrigin() {
        return origin == null ? "upload" : origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getExternalDataSourceId() {
        return externalDataSourceId;
    }

    public void setExternalDataSourceId(String externalDataSourceId) {
        this.externalDataSourceId = externalDataSourceId;
    }

    public String getSourceSQL() {
        return sourceSQL;
    }

    public void setSourceSQL(String sourceSQL) {
        this.sourceSQL = sourceSQL;
    }

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public DatasetEntity() {}

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

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getColumnSchemaJson() {
        return columnSchemaJson;
    }

    public void setColumnSchemaJson(String columnSchemaJson) {
        this.columnSchemaJson = columnSchemaJson;
    }

    public long getRowCount() {
        return rowCount;
    }

    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
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
