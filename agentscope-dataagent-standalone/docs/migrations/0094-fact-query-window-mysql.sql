-- Apply once to the platform database before deploying with ddl-auto=validate (ADR 0097–0099,
-- spec 083–085: per-table query windows for external fact tables). With the default
-- ddl-auto=update Hibernate creates the same objects automatically.
-- Existing knowledge bases need no data migration: tables without a row are configured on the
-- next sampling, and the group-level default stays 31 days.

-- Group-level default window (days) offered when a fact table is first configured.
ALTER TABLE dataagent_dataset_group
    ADD COLUMN fact_query_window_days INT NULL DEFAULT 31;

-- One row per external fact table: its time column(s), whether queries are limited, and how
-- many days one query may cover. Stored outside the MDL and effective without publishing.
CREATE TABLE IF NOT EXISTS dataagent_fact_query_window (
    window_id        VARCHAR(64)  NOT NULL,
    group_id         VARCHAR(64)  NOT NULL,
    dataset_id       VARCHAR(64)  NOT NULL,
    table_name       VARCHAR(191) NOT NULL,
    temporal_columns TEXT         NOT NULL,
    mode             VARCHAR(16)  NOT NULL,
    window_days      INT          NULL,
    updated_by       VARCHAR(128) NULL,
    updated_at       DATETIME(6)  NULL,
    PRIMARY KEY (window_id),
    UNIQUE KEY ux_fact_window_dataset (group_id, dataset_id),
    KEY ix_fact_window_group (group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
