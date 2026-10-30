-- spec104: per-user new-user onboarding state (checklist days, dismissal, milestones, tips).
-- Hibernate ddl-auto=update adds these columns automatically; run manually where it is disabled.
ALTER TABLE dataagent_user ADD COLUMN onboarding_started_at DATETIME(6) NULL;
ALTER TABLE dataagent_user ADD COLUMN onboarding_dismissed_at DATETIME(6) NULL;
ALTER TABLE dataagent_user ADD COLUMN onboarding_completed_at DATETIME(6) NULL;
ALTER TABLE dataagent_user ADD COLUMN onboarding_milestones VARCHAR(200) NULL;
ALTER TABLE dataagent_user ADD COLUMN onboarding_tips_seen VARCHAR(200) NULL;
