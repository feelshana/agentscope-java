-- ---------------------------------------------------------------------------
-- MySQL DDL + seed data for the tenant storage analytics tables.
--
-- Target database : test_data  (127.0.0.1:3306/test_data)
-- Tables          : tenant_storage_utilization, project_info
-- Relationship    : tenant_storage_utilization.project_name
--                   → project_info.project_name  (logical FK)
--
-- Column names are in English for maximum SQL compatibility; Chinese
-- descriptions are provided in column COMMENT clauses and are returned
-- by the GET /api/agents/data-agent/schema endpoint.
--
-- The script is idempotent:  CREATE TABLE IF NOT EXISTS + DELETE + INSERT
-- so it can be re-run safely after schema or data changes.
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS test_data
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE test_data;

-- ── Table 1: tenant_storage_utilization ──────────────────────────────────

CREATE TABLE IF NOT EXISTS tenant_storage_utilization (
  id                    INT            NOT NULL AUTO_INCREMENT COMMENT '主键',
  project_name          VARCHAR(64)    NOT NULL                COMMENT '项目名',
  db_name               VARCHAR(64)    NOT NULL                COMMENT '库',
  table_name            VARCHAR(64)    NOT NULL                COMMENT '表',
  size_gb               DECIMAL(10,2)  NOT NULL                COMMENT '表大小(GB)',
  file_count            INT            NOT NULL                COMMENT '表文件数',
  dir_path              VARCHAR(256)   NOT NULL                COMMENT '表目录树',
  uncompressed_size_gb  DECIMAL(10,2)  NOT NULL                COMMENT '未压缩文件大小(GB)',
  small_file_size_gb    DECIMAL(10,2)  NOT NULL                COMMENT '小文件大小(GB)',
  expired_file_size_gb  DECIMAL(10,2)  NOT NULL                COMMENT '过期文件大小(GB)',
  expired_file_count    INT            NOT NULL                COMMENT '过期文件数量',
  PRIMARY KEY (id),
  INDEX idx_project (project_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── Table 2: project_info ────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS project_info (
  id            INT           NOT NULL AUTO_INCREMENT COMMENT '项目ID',
  project_name  VARCHAR(64)   NOT NULL                COMMENT '项目名称',
  owner_name    VARCHAR(32)   NOT NULL                COMMENT '负责人名称',
  PRIMARY KEY (id),
  UNIQUE INDEX uk_project (project_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── Seed: project_info (10 tenants) ──────────────────────────────────────

DELETE FROM project_info;

INSERT INTO project_info (id, project_name, owner_name) VALUES
  (1,  'data-lake',         '张伟'),
  (2,  'user-profile',      '李娜'),
  (3,  'ml-pipeline',       '王磊'),
  (4,  'log-analysis',      '赵敏'),
  (5,  'order-system',      '刘洋'),
  (6,  'search-engine',     '陈静'),
  (7,  'recommendation',    '周强'),
  (8,  'risk-control',      '吴芳'),
  (9,  'payment-gateway',   '孙浩'),
  (10, 'content-platform',  '马丽');

-- ── Seed: tenant_storage_utilization (33 rows, multi-table per project) ──

DELETE FROM tenant_storage_utilization;

INSERT INTO tenant_storage_utilization
  (project_name, db_name, table_name, size_gb, file_count, dir_path,
   uncompressed_size_gb, small_file_size_gb, expired_file_size_gb, expired_file_count)
VALUES
  -- data-lake (张伟) — 管理最差: 500GB 总存储, 55% 可优化, 31000 过期文件
  ('data-lake', 'lake_prod', 'raw_events',    200.00, 15000, '/data/lake/raw_events',    180.00, 45.00,  80.00, 12000),
  ('data-lake', 'lake_prod', 'click_stream',  150.00, 12000, '/data/lake/click_stream',  135.00, 30.00,  50.00,  8000),
  ('data-lake', 'lake_prod', 'user_actions',  150.00,  8000, '/data/lake/user_actions',  130.00, 15.00,  25.00, 11000),

  -- user-profile (李娜) — 管理较差: 265GB, 40% 可优化, 26000 过期文件
  ('user-profile', 'profile_db', 'user_base',    80.00,  5000, '/data/profile/user_base',    72.00, 20.00, 15.00,  8000),
  ('user-profile', 'profile_db', 'user_tags',    60.00,  8000, '/data/profile/user_tags',    54.00, 25.00, 10.00, 10000),
  ('user-profile', 'profile_db', 'user_behavior',125.00, 10000, '/data/profile/user_behavior',110.00, 18.00, 22.00,  8000),

  -- ml-pipeline (王磊) — 管理较差: 315GB, 46% 可优化, 大量过期文件
  ('ml-pipeline', 'ml_db', 'feature_store',  200.00,  6000, '/data/ml/feature_store',  180.00, 40.00,  75.00,  5000),
  ('ml-pipeline', 'ml_db', 'model_logs',      80.00,  4000, '/data/ml/model_logs',      72.00, 15.00,  35.00,  3000),
  ('ml-pipeline', 'ml_db', 'training_data',    35.00,  2000, '/data/ml/training_data',   30.00,  5.00,  12.00,  1000),

  -- log-analysis (赵敏) — 中等偏差: 170GB, 32% 可优化
  ('log-analysis', 'log_db', 'access_log',  100.00,  8000, '/data/log/access_log',  90.00, 25.00, 20.00, 5000),
  ('log-analysis', 'log_db', 'error_log',    20.00,  1500, '/data/log/error_log',   18.00,  3.00,  2.00,  800),
  ('log-analysis', 'log_db', 'audit_log',    50.00,  3000, '/data/log/audit_log',   45.00, 10.00,  8.00, 2000),

  -- order-system (刘洋) — 中等: 175GB, 23% 可优化
  ('order-system', 'order_db', 'orders',       80.00, 3000, '/data/order/orders',       72.00, 10.00,  8.00, 2000),
  ('order-system', 'order_db', 'order_items',  60.00, 2500, '/data/order/order_items',  54.00,  8.00,  5.00, 1500),
  ('order-system', 'order_db', 'payments',     35.00, 1800, '/data/order/payments',     30.00,  5.00,  3.00, 1000),

  -- search-engine (陈静) — 中等偏好: 150GB, 可优化主要来自小文件
  ('search-engine', 'search_db', 'index_data',  100.00, 80000, '/data/search/index_data',  90.00, 30.00,  5.00,  500),
  ('search-engine', 'search_db', 'query_log',    50.00, 40000, '/data/search/query_log',   45.00, 15.00,  2.00,  300),

  -- recommendation (周强) — 管理好: 120GB, 仅 11% 可优化
  ('recommendation', 'rec_db', 'user_vectors',  50.00, 2000, '/data/rec/user_vectors',  45.00,  2.00,  1.00,  200),
  ('recommendation', 'rec_db', 'item_vectors',  50.00, 2000, '/data/rec/item_vectors',  45.00,  3.00,  0.50,  100),
  ('recommendation', 'rec_db', 'model_params',  20.00,  500, '/data/rec/model_params',  18.00,  5.00,  0.50,  50),

  -- risk-control (吴芳) — 管理好: 86GB, 13% 可优化, 极少过期文件
  ('risk-control', 'risk_db', 'risk_events',  50.00, 3000, '/data/risk/risk_events',  45.00,  5.00,  2.00,  300),
  ('risk-control', 'risk_db', 'blacklist',     1.00,  200, '/data/risk/blacklist',     0.90,  0.10,  0.05,   20),
  ('risk-control', 'risk_db', 'rules',         0.50,  100, '/data/risk/rules',         0.45,  0.05,  0.02,   10),
  ('risk-control', 'risk_db', 'alerts',       35.00, 2500, '/data/risk/alerts',       30.00,  6.00,  3.00,  500),

  -- payment-gateway (孙浩) — 中等偏好: 76GB, 可优化占比低
  ('payment-gateway', 'pay_db', 'transactions', 60.00, 4000, '/data/pay/transactions', 54.00,  5.00,  3.00, 1000),
  ('payment-gateway', 'pay_db', 'refunds',      10.00,  800, '/data/pay/refunds',       9.00,  1.00,  0.50,  200),
  ('payment-gateway', 'pay_db', 'settlements',   6.00,  500, '/data/pay/settlements',   5.40,  0.50,  0.30,  100),

  -- content-platform (马丽) — 管理较好: 106GB, 24% 可优化
  ('content-platform', 'content_db', 'articles',   40.00, 3000, '/data/content/articles',   36.00,  5.00,  3.00,  800),
  ('content-platform', 'content_db', 'comments',   30.00, 5000, '/data/content/comments',   27.00,  8.00,  2.00, 1000),
  ('content-platform', 'content_db', 'media_refs', 36.00, 2000, '/data/content/media_refs', 32.00,  4.00,  8.00, 2500);
