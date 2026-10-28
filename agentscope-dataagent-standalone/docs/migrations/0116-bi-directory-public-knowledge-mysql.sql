-- Existing installations only; back up first and execute once before validate startup.
-- Empty installations use the updated 0000 initial schema instead.
ALTER TABLE dataagent_user
    ADD COLUMN display_name varchar(191) NULL,
    ADD COLUMN company varchar(191) NULL,
    ADD COLUMN department varchar(191) NULL;
ALTER TABLE dataagent_dataset_group
    ADD COLUMN public_query bit(1) NOT NULL DEFAULT b'0';
