-- Platform database only. Existing datasource passwords are not modified.
-- For an existing database, check INFORMATION_SCHEMA and execute only missing changes.
-- Do not re-run the ALTER if enabled already exists. Never run against the uploaded-data database.
ALTER TABLE dataagent_external_datasource
    ADD COLUMN enabled BIT(1) NOT NULL DEFAULT b'1';

CREATE TABLE IF NOT EXISTS dataagent_datasource_grant (
    id VARCHAR(64) NOT NULL,
    datasource_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    role VARCHAR(16) NOT NULL,
    granted_by VARCHAR(128) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ix_ds_grant_source_user (datasource_id, user_id),
    KEY ix_ds_grant_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dataagent_datasource_audit (
    id VARCHAR(64) NOT NULL,
    datasource_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NULL,
    action VARCHAR(32) NOT NULL,
    previous_role VARCHAR(16) NULL,
    new_role VARCHAR(16) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_ds_audit_source (datasource_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
