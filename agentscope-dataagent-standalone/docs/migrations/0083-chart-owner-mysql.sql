-- Run once in the platform metadata database before deploying with ddl-auto=validate.
-- Inspect INFORMATION_SCHEMA.COLUMNS first; skip ALTER when owner_id already exists.
ALTER TABLE dataagent_chart_option ADD COLUMN owner_id VARCHAR(128) NULL;

-- The primary key chart_id already limits the lookup to one row; no extra index is needed.
-- Legacy NULL-owner rows remain stored but are inaccessible through GET /api/charts/{id}.
-- Do not assign legacy rows to an administrator or infer ownership from chart references.
