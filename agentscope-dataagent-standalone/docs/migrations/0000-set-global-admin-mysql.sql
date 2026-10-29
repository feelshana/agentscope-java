-- Replace every occurrence of REPLACE_WITH_BI_ACCOUNT with the BI loginname/username.
-- Import the account first, or let the account enter through BI once.
-- This script does not create accounts or change passwords/knowledge-base grants.
SELECT `username`,`display_name`,`company`,`roles_csv`
FROM `dataagent_platform`.`dataagent_user`
WHERE `username`='REPLACE_WITH_BI_ACCOUNT';

UPDATE `dataagent_platform`.`dataagent_user`
SET `roles_csv`=CONCAT_WS(',',COALESCE(NULLIF(`roles_csv`,''),'user'),'admin')
WHERE `username`='REPLACE_WITH_BI_ACCOUNT'
  AND FIND_IN_SET('admin',COALESCE(`roles_csv`,''))=0;

SELECT `username`,`display_name`,`company`,`roles_csv`
FROM `dataagent_platform`.`dataagent_user`
WHERE `username`='REPLACE_WITH_BI_ACCOUNT';
-- Re-enter through the BI icon to obtain a fresh platform login token.
