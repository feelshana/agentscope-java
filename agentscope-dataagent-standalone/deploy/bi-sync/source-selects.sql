-- Reference projection for review. Runtime uses parameters and only the fixed tables below.
-- Never SELECT *: future source columns must not silently enter the public knowledge base.
SELECT id, name, leader FROM easybi.sys_project;
SELECT id, operatorid, projectid, createtime FROM easybi.sys_project_operator_relation;
SELECT id, loginname, name, bak_name, type, createtime, source,
       lastloginprojectid, spmsaccount, firstloginflag, state, passwordlastupdate,
       company, dept, tip_count, select_area_tip_count, user_type, posts
FROM easybi.sys_operator;
SELECT id, name, org_id, last_heartbeat_time, editing_session,
       editing_operator, description, creator, create_time, update_time,
       state, org_name, datasource_ids, model_ids, auto_refresh,
       refresh_time, is_dark, assets, can_download, can_subscribe,
       can_comment, view_sql, show_dashboard_title, is_hot, title_align,
       min_height, background, is_template, template_image, hide_top_right,
       is_hide_drill_or_link_btn, temp, is_mobile, dashboard_type, pre_shutdown_status,
       ttl, unit, subcategory, requester
FROM easybi.d_dashboard;
SELECT id, name, org_id, last_heartbeat_time, editing_session,
       description, creator, create_time, update_time, state,
       type, org_name, remark, assets, is_hot,
       training_status, training_time, agent_id, agent_name, source_id,
       update_config_time, pre_shutdown_status, requester
FROM easybi.d_report;

-- Internal enumeration only; project columns are not in the four-column target logs.
SELECT DISTINCT report_project_id FROM easybi.d_report_log;
SELECT DISTINCT dashboard_project_id FROM easybi.d_dashboard_log;

-- Examples only. Runtime substitutes each log project and the pending date interval.
-- All operation types are retained. No log_type or has_permission predicate is added.
SELECT id, log_time, report_id, operator_id
FROM easybi.d_report_log
WHERE report_project_id = 'REPLACE_WITH_LOG_PROJECT'
  AND log_time >= '2026-11-02 00:00:00' AND log_time < '2026-11-03 00:00:00'
ORDER BY log_time, id;
SELECT id, log_time, dashboard_id, operator_id
FROM easybi.d_dashboard_log
WHERE dashboard_project_id = 'REPLACE_WITH_LOG_PROJECT'
  AND log_time >= '2026-11-02 00:00:00' AND log_time < '2026-11-03 00:00:00'
ORDER BY log_time, id;
