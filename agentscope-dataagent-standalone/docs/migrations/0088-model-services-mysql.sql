-- Apply to the platform metadata database before starting ADR0088 with validate.
-- The payload contains encrypted drafts, the active snapshot and switch metadata.
CREATE TABLE IF NOT EXISTS dataagent_model_service_config (
    config_id VARCHAR(32) NOT NULL PRIMARY KEY,
    encrypted_payload LONGTEXT NOT NULL,
    version BIGINT NOT NULL
);
