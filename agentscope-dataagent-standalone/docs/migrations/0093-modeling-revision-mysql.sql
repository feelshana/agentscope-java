-- Optional MySQL migration for automatic semantic-modeling revision tracking.
ALTER TABLE dataagent_dataset_group
    ADD COLUMN modeling_revision BIGINT NULL DEFAULT 0,
    ADD COLUMN published_modeling_revision BIGINT NULL DEFAULT 0;
