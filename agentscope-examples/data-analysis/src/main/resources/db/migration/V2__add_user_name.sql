-- ======================================================
-- V2: Add user_name column to chat_session for multi-user isolation
-- ======================================================

ALTER TABLE chat_session
    ADD COLUMN user_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '用户标识（来自URL参数 userName）' AFTER id;

-- Index for fast per-user history queries
CREATE INDEX idx_user_name_updated ON chat_session (user_name, updated_at DESC);
