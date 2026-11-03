-- Read-only BI source checks. Run against easybi, not the platform database.
-- No credentials, DDL, DML, CTEs or window functions are used here.
-- Date example: yesterday relative to 2026-11-02 in Asia/Shanghai.
-- Replace BOTH date literals when testing on another day.
-- BI consoles may use a different connection for every statement: no session variables.

SELECT VERSION() AS mysql_version, DATABASE() AS selected_database,
       @@session.time_zone AS session_time_zone, NOW() AS database_now;

-- A matching orphan count is evidence about current data, not a declared foreign key.
-- Neither query joins the project/operator many-to-many bridge to access events.
SELECT 'report.org_id -> project.id' AS relation_check,
       COUNT(*) AS object_rows,
       COALESCE(SUM(p.id IS NULL), 0) AS missing_project_rows
FROM easybi.d_report r
LEFT JOIN easybi.sys_project p ON p.id = r.org_id
UNION ALL
SELECT 'dashboard.org_id -> project.id', COUNT(*),
       COALESCE(SUM(p.id IS NULL), 0)
FROM easybi.d_dashboard d
LEFT JOIN easybi.sys_project p ON p.id = d.org_id;

-- Review missing project examples if the preceding counts are nonzero.
SELECT r.id, r.name, r.org_id
FROM easybi.d_report r
LEFT JOIN easybi.sys_project p ON p.id = r.org_id
WHERE p.id IS NULL
LIMIT 20;

SELECT d.id, d.name, d.org_id
FROM easybi.d_dashboard d
LEFT JOIN easybi.sys_project p ON p.id = d.org_id
WHERE p.id IS NULL
LIMIT 20;

SELECT COUNT(*) AS relation_rows,
       COALESCE(SUM(p.id IS NULL), 0) AS missing_project_rows,
       COALESCE(SUM(o.id IS NULL), 0) AS missing_operator_rows
FROM easybi.sys_project_operator_relation r
LEFT JOIN easybi.sys_project p ON p.id = r.projectid
LEFT JOIN easybi.sys_operator o ON o.id = r.operatorid;

SELECT projectid, operatorid, COUNT(*) AS duplicate_pair_count
FROM easybi.sys_project_operator_relation
GROUP BY projectid, operatorid
HAVING COUNT(*) > 1
LIMIT 20;

-- Inspect these plans BEFORE running date-filtered log sampling or initial backfill.
-- Existing source indexes do not start with log_time in the supplied DDL.
-- A LIMIT on returned rows does not itself guarantee few rows scanned.
EXPLAIN
SELECT id, log_time, report_id, report_project_id,
       operator_id, operator_project_id, log_type, has_permission
FROM easybi.d_report_log
WHERE log_time >= '2026-11-01 00:00:00'
  AND log_time <  '2026-11-02 00:00:00'
ORDER BY log_time, id
LIMIT 1000;

EXPLAIN
SELECT id, log_time, dashboard_id, dashboard_project_id,
       operator_id, operator_project_id, log_type, has_permission
FROM easybi.d_dashboard_log
WHERE log_time >= '2026-11-01 00:00:00'
  AND log_time <  '2026-11-02 00:00:00'
ORDER BY log_time, id
LIMIT 1000;

-- Optional checks after the query plans are reviewed.
-- These are samples of at most 10000 events PER table, not full-data assertions.
-- A count of 10000 can mean truncation; zero missing rows proves only this sample.
SELECT 'report_log_sample' AS sample_scope,
       x.log_type, x.has_permission, COUNT(*) AS sampled_events,
       COALESCE(SUM(r.id IS NULL), 0) AS missing_object_rows,
       COALESCE(SUM(o.id IS NULL), 0) AS missing_operator_rows,
       COALESCE(SUM(p.id IS NULL), 0) AS missing_event_project_rows
FROM (
    SELECT report_id, report_project_id, operator_id, log_type, has_permission
    FROM easybi.d_report_log
    WHERE log_time >= '2026-11-01 00:00:00'
      AND log_time <  '2026-11-02 00:00:00'
    LIMIT 10000
) x
LEFT JOIN easybi.d_report r ON r.id = x.report_id
LEFT JOIN easybi.sys_operator o ON o.id = x.operator_id
LEFT JOIN easybi.sys_project p ON p.id = x.report_project_id
GROUP BY x.log_type, x.has_permission;

SELECT 'dashboard_log_sample' AS sample_scope,
       x.log_type, x.has_permission, COUNT(*) AS sampled_events,
       COALESCE(SUM(d.id IS NULL), 0) AS missing_object_rows,
       COALESCE(SUM(o.id IS NULL), 0) AS missing_operator_rows,
       COALESCE(SUM(p.id IS NULL), 0) AS missing_event_project_rows
FROM (
    SELECT dashboard_id, dashboard_project_id, operator_id, log_type, has_permission
    FROM easybi.d_dashboard_log
    WHERE log_time >= '2026-11-01 00:00:00'
      AND log_time <  '2026-11-02 00:00:00'
    LIMIT 10000
) x
LEFT JOIN easybi.d_dashboard d ON d.id = x.dashboard_id
LEFT JOIN easybi.sys_operator o ON o.id = x.operator_id
LEFT JOIN easybi.sys_project p ON p.id = x.dashboard_project_id
GROUP BY x.log_type, x.has_permission;
