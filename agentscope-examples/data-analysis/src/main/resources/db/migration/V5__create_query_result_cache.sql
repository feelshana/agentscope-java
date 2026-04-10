-- ======================================================
-- V5: Create query result cache table
-- Caches query_dataset results

-- ======================================================

CREATE TABLE `query_result_cache` (
                                      `id` bigint NOT NULL AUTO_INCREMENT COMMENT '记录ID',
                                      `session_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属会话ID',
                                      `dataset_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据集名称',
                                      `question` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '调用query_dataset时的question参数',
                                      `query_result` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'CSV格式数据结果',
                                      `user_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '',
                                      `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
                                      PRIMARY KEY (`id`),
                                      KEY `idx_session_id` (`session_id`),
                                      KEY `idx_session_round` (`session_id`),
                                      KEY `idx_session_created` (`session_id`,`created_at`),
                                      KEY `idx_query_result_cache_user_name` (`user_name`),
                                      CONSTRAINT `fk_cache_session` FOREIGN KEY (`session_id`) REFERENCES `chat_session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='query_dataset结果缓存表';