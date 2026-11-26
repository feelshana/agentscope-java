-- spec149 P7-2 / ADR0156: knowledge base mode (NORMAL | CALIBER), chosen at creation and never changed.
-- Existing installations only; back up first and execute once before validate startup.
-- Existing knowledge bases keep NULL, which the application reads as NORMAL (no migration of old lists).
-- Empty installations use the updated 0000 initial schema instead.
ALTER TABLE dataagent_dataset_group
    ADD COLUMN knowledge_mode varchar(16) COLLATE utf8mb4_unicode_ci NULL;
