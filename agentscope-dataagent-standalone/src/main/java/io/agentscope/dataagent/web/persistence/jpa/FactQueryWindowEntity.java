package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Explicit query-window configuration for one external fact table. */
@Entity
@Table(
        name = "dataagent_fact_query_window",
        indexes = {
            @Index(name = "ix_fact_window_group", columnList = "group_id"),
            @Index(
                    name = "ux_fact_window_dataset",
                    columnList = "group_id,dataset_id",
                    unique = true)
        })
public class FactQueryWindowEntity {
    @Id
    @Column(name = "window_id", length = 64, nullable = false)
    private String id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "dataset_id", length = 64, nullable = false)
    private String datasetId;

    @Column(name = "table_name", length = 191, nullable = false)
    private String tableName;

    @Column(name = "temporal_columns", columnDefinition = "TEXT", nullable = false)
    private String temporalColumnsJson;

    @Column(name = "mode", length = 16, nullable = false)
    private String mode;

    @Column(name = "window_days")
    private Integer windowDays;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    protected FactQueryWindowEntity() {}

    public FactQueryWindowEntity(
            String id,
            String groupId,
            String datasetId,
            String tableName,
            String temporalColumnsJson,
            String mode,
            Integer windowDays,
            String updatedBy) {
        this.id = id;
        this.groupId = groupId;
        this.datasetId = datasetId;
        this.tableName = tableName;
        this.temporalColumnsJson = temporalColumnsJson;
        this.mode = mode;
        this.windowDays = windowDays;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public String getTableName() {
        return tableName;
    }

    public String getTemporalColumnsJson() {
        return temporalColumnsJson;
    }

    public String getMode() {
        return mode;
    }

    public Integer getWindowDays() {
        return windowDays;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(
            String temporalColumnsJson, String mode, Integer windowDays, String updatedBy) {
        this.temporalColumnsJson = temporalColumnsJson;
        this.mode = mode;
        this.windowDays = windowDays;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }
}
