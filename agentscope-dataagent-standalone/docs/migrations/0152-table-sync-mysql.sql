-- Manually run in BI Data Development on the existing production platform database.
-- These statements only add four metadata tables. The runtime never executes this file.

CREATE TABLE IF NOT EXISTS `data_agent_platform`.`dataagent_table_sync_target` (
    id INT PRIMARY KEY,
    host VARCHAR(253) NOT NULL,
    port INT NOT NULL,
    username VARCHAR(128) NOT NULL,
    password VARCHAR(1024) NOT NULL,
    ssl_mode VARCHAR(16) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `data_agent_platform`.`dataagent_table_sync_task` (
    id CHAR(32) CHARACTER SET ascii PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    target_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    config JSON NOT NULL,
    credential TEXT NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    initial_from DATE NULL,
    initial_through DATE NULL,
    initialized BOOLEAN NOT NULL DEFAULT FALSE,
    schedule_time TIME NULL,
    schedule_enabled_at DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `data_agent_platform`.`dataagent_table_sync_run` (
    id CHAR(32) CHARACTER SET ascii PRIMARY KEY,
    task_id CHAR(32) CHARACTER SET ascii NOT NULL,
    kind VARCHAR(12) CHARACTER SET ascii NOT NULL,
    slot DATE NULL,
    date_from DATE NULL,
    date_through DATE NULL,
    accept_empty BOOLEAN NOT NULL DEFAULT FALSE,
    actor VARCHAR(255) NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii NOT NULL DEFAULT 'QUEUED',
    phase VARCHAR(24) CHARACTER SET ascii NULL,
    progress_date DATE NULL,
    rows_read BIGINT UNSIGNED NOT NULL DEFAULT 0,
    rows_committed BIGINT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(32) CHARACTER SET ascii NULL,
    queued_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    UNIQUE KEY uq_sync_schedule (task_id, slot),
    KEY ix_sync_queue (state, queued_at),
    KEY ix_sync_history (task_id, queued_at),
    FOREIGN KEY (task_id) REFERENCES `data_agent_platform`.`dataagent_table_sync_task`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `data_agent_platform`.`dataagent_table_sync_coverage` (
    task_id CHAR(32) CHARACTER SET ascii NOT NULL,
    business_date DATE NOT NULL,
    run_id CHAR(32) CHARACTER SET ascii NOT NULL,
    row_count BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (task_id, business_date),
    KEY ix_sync_coverage_run (run_id),
    FOREIGN KEY (task_id) REFERENCES `data_agent_platform`.`dataagent_table_sync_task`(id),
    FOREIGN KEY (run_id) REFERENCES `data_agent_platform`.`dataagent_table_sync_run`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
