-- Existing platform database only. Check SHOW COLUMNS/SHOW INDEX first; run only missing clauses.
-- New databases already include these fields in 0000-initial-platform-mysql.sql.
ALTER TABLE session_message ADD COLUMN message_role VARCHAR(16) NULL;
ALTER TABLE session_message ADD COLUMN user_text LONGTEXT NULL;
CREATE INDEX ix_message_role ON session_message(session_key, message_role);
CREATE INDEX ix_message_projection ON session_message(message_role);
-- Existing transcript payloads stay intact. The application backfills the projection in bounded batches.
