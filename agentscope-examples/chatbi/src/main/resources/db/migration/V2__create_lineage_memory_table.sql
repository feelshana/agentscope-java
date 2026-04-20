-- ======================================================
-- V2: Create data_lineage_memory table for DataLineageAgent
-- Stores the entire conversation history as a JSON array
-- in a single row per session, appended each round.
-- ======================================================

CREATE TABLE IF NOT EXISTS data_lineage_memory (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    session_id  VARCHAR(128)  NOT NULL COMMENT '会话ID',
    content     LONGTEXT      NOT NULL COMMENT '对话历史JSON数组，格式：[{role,text,time},...]',
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据血缘Agent对话记忆表';
