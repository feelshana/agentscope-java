-- spec105: demo_data.report_visits.visit_time becomes DATETIME.
-- Run on the dataset MySQL. Only for a demo_data that already holds the text column;
-- DemoDataService does the same on startup when its account may alter the table.
-- 2026/9/31 does not exist in the calendar; those rows move to 2026-09-30, keeping their time.
UPDATE demo_data.report_visits
SET visit_time = CONCAT('2026-09-30 ', LPAD(SUBSTRING_INDEX(visit_time, ' ', -1), 5, '0'), ':00')
WHERE visit_time LIKE '2026/9/31 %';

ALTER TABLE demo_data.report_visits MODIFY visit_time DATETIME COMMENT '访问时间';
