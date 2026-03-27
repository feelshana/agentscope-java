-- ======================================================
-- V1: Create chat session and message tables
-- ======================================================

-- Chat session table: one record per conversation
CREATE TABLE IF NOT EXISTS chat_session (
    id           VARCHAR(36)   NOT NULL COMMENT '会话ID (UUID)',
    title        VARCHAR(255)  NOT NULL DEFAULT '新对话' COMMENT '会话标题（取第一条用户消息截断）',
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    message_count INT          NOT NULL DEFAULT 0 COMMENT '消息总数',
    is_summarized TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '历史是否已被摘要压缩',
    summary_text  MEDIUMTEXT   NULL COMMENT '压缩摘要文本',
    PRIMARY KEY (id),
    INDEX idx_updated_at (updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话表';

-- Chat message table: each turn of conversation
CREATE TABLE IF NOT EXISTS chat_message (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    session_id   VARCHAR(36)   NOT NULL COMMENT '所属会话ID',
    role         VARCHAR(16)   NOT NULL COMMENT '消息角色: user/assistant/system',
    content      MEDIUMTEXT    NOT NULL COMMENT '消息内容',
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_session_id (session_id),
    INDEX idx_session_created (session_id, created_at ASC),
    CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表';
