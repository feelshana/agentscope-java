-- Fresh installation: names match the application's current defaults.
-- Run with an account allowed to create databases; no application users are created here.
-- Existing databases are not reset or emptied by this script.
CREATE DATABASE IF NOT EXISTS `dataagent_platform`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `data_agent`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
