-- Apply once to the platform database before deploying ADR 0080 with ddl-auto=validate.
-- Existing records retain NULL until an explicit refresh or a new import.
ALTER TABLE dataagent_dataset ADD COLUMN sample_summary_json LONGTEXT NULL;
