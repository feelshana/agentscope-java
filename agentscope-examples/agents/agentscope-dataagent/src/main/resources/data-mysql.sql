-- ---------------------------------------------------------------------------
-- MySQL seed data (runs when spring.sql.init.platform=mysql, which is the
-- default configuration).
--
-- Provides two demo accounts so a fresh local checkout can be exercised
-- end-to-end without first calling the admin API to create users:
--
--   bob   / bob    (role: user)
--   alice / alice  (role: user)
--
-- The password column stores BCrypt hashes ( cost = 10 ). These are
-- precomputed and committed verbatim — BCrypt embeds its own random salt in
-- the hash, so the same constant is verified successfully by Spring's
-- BCryptPasswordEncoder on every JVM.
--
-- `INSERT ... ON DUPLICATE KEY UPDATE` makes the seed idempotent — running
-- this script after the rows already exist updates them in place rather than
-- failing on the PK/UK constraint, so application.yml safely runs it on
-- every startup (`spring.sql.init.mode=always`).
-- ---------------------------------------------------------------------------

INSERT INTO dataagent_user (user_id, username, password_hash, roles_csv, created_at) VALUES
  ('bob',   'bob',   '$2y$10$jlcRmUapzgu.P95uby8yx.1KSxQe.7Hmj0.fDJlg4OAOgfMruaU2y', 'user', CURRENT_TIMESTAMP),
  ('alice', 'alice', '$2y$10$KO33XwJbFTsFGTPIlrvLe.xvI0tK3PNKVNVGpHNQyMZhzDxVdoKA6', 'user', CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
  username = VALUES(username),
  password_hash = VALUES(password_hash),
  roles_csv = VALUES(roles_csv);
