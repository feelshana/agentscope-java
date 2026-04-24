-- ======================================================
-- V2: Create sub_agent_memory table for sub-agents
-- Stores the entire conversation history as a JSON array
-- in a single row per session + type combination.
-- ======================================================

CREATE TABLE IF NOT EXISTS sub_agent_memory (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    session_id  VARCHAR(128)  NOT NULL COMMENT '会话ID',
    type        VARCHAR(16)   NOT NULL DEFAULT 'dl' COMMENT '意图类型: dl=血缘, re=报表推荐',
    content     LONGTEXT      NOT NULL COMMENT '对话历史JSON数组，格式：[{role,text,time},...]',
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_session_type (session_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='子Agent对话记忆表';
