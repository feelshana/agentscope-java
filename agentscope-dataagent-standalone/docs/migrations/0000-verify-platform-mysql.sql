-- Run each SELECT independently if the SQL editor accepts one statement at a time.
SELECT DATABASE() AS selected_database, VERSION() AS mysql_version;

-- The complete initial platform schema currently contains 37 base tables.
SELECT COUNT(*) AS platform_table_count
FROM information_schema.tables
WHERE table_schema='dataagent_platform' AND table_type='BASE TABLE';

-- The upload database has no tables until files have actually been uploaded.
SELECT COUNT(*) AS upload_table_count
FROM information_schema.tables
WHERE table_schema='data_agent' AND table_type='BASE TABLE';

-- An empty platform receiving the supplied directory has 1484 users before new BI arrivals.
SELECT COUNT(*) AS total_users,
       COALESCE(SUM(FIND_IN_SET('admin',COALESCE(roles_csv,''))>0),0) AS global_admins
FROM `dataagent_platform`.`dataagent_user`;

SELECT `username`,`display_name`,`company`,`department`,`roles_csv`
FROM `dataagent_platform`.`dataagent_user`
ORDER BY `username` LIMIT 20;
