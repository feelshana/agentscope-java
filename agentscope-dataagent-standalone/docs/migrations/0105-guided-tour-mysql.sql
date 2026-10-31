-- spec105: guided tour position and onboarding reset time. Needed only where hibernate ddl-auto does not add columns.
ALTER TABLE dataagent_user ADD COLUMN onboarding_tour VARCHAR(200) NULL;
ALTER TABLE dataagent_user ADD COLUMN onboarding_reset_at DATETIME(6) NULL;
