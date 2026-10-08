-- Additive migration for deployments using spring.jpa.hibernate.ddl-auto=validate.
-- Back up the platform database and runtime workspace before applying.
CREATE TABLE IF NOT EXISTS session_history (
  session_key VARCHAR(255) NOT NULL PRIMARY KEY,
  session_id VARCHAR(255), title VARCHAR(160), preview VARCHAR(500),
  activity_ms BIGINT NOT NULL, file_stamp BIGINT NOT NULL, file_size BIGINT NOT NULL,
  source_path VARCHAR(2048), message_count INT NOT NULL
);

CREATE TABLE IF NOT EXISTS session_message (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  session_key VARCHAR(255) NOT NULL, position_no INT NOT NULL,
  payload LONGTEXT NOT NULL,
  UNIQUE KEY ix_message_order (session_key, position_no)
);

CREATE TABLE IF NOT EXISTS session_artifact (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(128) NOT NULL, session_id VARCHAR(255) NOT NULL,
  run_id VARCHAR(36) NOT NULL, filename VARCHAR(255) NOT NULL,
  source_path VARCHAR(1024) NOT NULL, size_bytes BIGINT NOT NULL,
  created_at_ms BIGINT NOT NULL, input_data BOOLEAN NOT NULL,
  KEY ix_artifact_session (owner_id, session_id),
  KEY ix_artifact_owner (owner_id)
);
