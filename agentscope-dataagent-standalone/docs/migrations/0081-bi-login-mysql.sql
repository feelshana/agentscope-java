-- Run in the platform metadata database before using ddl-auto=validate.
CREATE TABLE IF NOT EXISTS dataagent_bi_login_ticket (
    request_id VARCHAR(36) NOT NULL PRIMARY KEY,
    expires_at DATETIME(6) NOT NULL,
    INDEX ix_bi_ticket_expiry (expires_at)
);

-- Administrator maintenance: replace the username list with actual BI usernames.
-- Preserve any other application roles when editing an existing account.
-- UPDATE dataagent_user SET roles_csv='user,admin' WHERE username IN ('your_bi_username');
-- UPDATE dataagent_user SET roles_csv='user' WHERE username IN ('your_bi_username');
