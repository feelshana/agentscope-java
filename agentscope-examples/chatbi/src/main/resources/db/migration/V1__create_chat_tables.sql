-- ======================================================
-- V1: Create chat session and message tables for ChatBI
-- ======================================================

CREATE TABLE IF NOT EXISTS chat_session (
    id            VARCHAR(36)   NOT NULL COMMENT '会话ID (UUID)',
    title         VARCHAR(255)  NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    user_name     VARCHAR(128)  NOT NULL DEFAULT '' COMMENT '用户名',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    message_count INT           NOT NULL DEFAULT 0,
    is_summarized TINYINT(1)    NOT NULL DEFAULT 0,
    summary_text  MEDIUMTEXT    NULL,
    PRIMARY KEY (id),
    INDEX idx_user_updated (user_name, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ChatBI对话会话表';

CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  VARCHAR(36)  NOT NULL,
    role        VARCHAR(16)  NOT NULL COMMENT 'user/assistant/system',
    content     MEDIUMTEXT   NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_session_created (session_id, created_at ASC),
    CONSTRAINT fk_chatbi_message_session FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ChatBI对话消息表';
