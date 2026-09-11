-- ======================================================
-- V4: Create LLM interaction log table
-- Records each round of LLM call in a ReActAgent iteration
-- ======================================================

CREATE TABLE IF NOT EXISTS llm_interaction_log (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    session_id     VARCHAR(36)   NOT NULL COMMENT '所属会话ID',
    iter           INT           NOT NULL DEFAULT 0 COMMENT 'ReAct 迭代轮次（从0开始）',
    user_question  MEDIUMTEXT    NULL COMMENT '本次会话用户原始问题',
    messages_json  LONGTEXT      NOT NULL COMMENT '传给LLM的消息列表（JSON数组），SYSTEM消息仅存标识占位符',
    llm_response   LONGTEXT      NULL COMMENT 'LLM响应内容（文本+工具调用序列化）',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间',
    PRIMARY KEY (id),
    INDEX idx_session_id (session_id),
    INDEX idx_session_iter (session_id, iter ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM交互轮次日志表';
