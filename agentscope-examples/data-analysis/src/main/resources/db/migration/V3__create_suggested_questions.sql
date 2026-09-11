-- ======================================================
-- V3: Create suggested questions table
-- ======================================================

CREATE TABLE IF NOT EXISTS suggested_question (
    id          INT           NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    question    VARCHAR(500)  NOT NULL COMMENT '推荐问题文本（可含 emoji）',
    sort_order  INT           NOT NULL DEFAULT 0 COMMENT '显示顺序，越小越靠前',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0禁用',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
    PRIMARY KEY (id),
    INDEX idx_sort_order (sort_order ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='前端推荐问题配置表';

-- 初始化默认推荐问题
INSERT INTO suggested_question (question, sort_order, enabled) VALUES
('集团考核指标完成情况？',         1, 1),
('咪咕视频社区化指标有哪些',       2, 1),
('咪咕视频社区化业务发展情况？',   3, 1),
('最近一周咪咕视频高质量用户分析', 4, 1),
('咪咕视频日指数是什么含义',       5, 1),
('昨日有些什么热门内容？',         6, 1);
