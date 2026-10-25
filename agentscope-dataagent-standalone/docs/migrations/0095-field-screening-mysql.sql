-- Apply once to the platform database before deploying with ddl-auto=validate (spec 093: field
-- screening before modeling). With the default ddl-auto=update Hibernate adds it automatically.
-- NULL means the dataset predates field screening and counts as confirmed (all columns included).
-- Per-column choices (included / role / roleReason / descriptionSource) live in the existing
-- column_schema_json and need no migration.
ALTER TABLE dataagent_dataset
    ADD COLUMN field_screening_confirmed BOOLEAN NULL;
