-- ---------------------------------------------------------------------------
-- H2-only seed data (runs when spring.sql.init.platform=h2 and the JDBC URL
-- points at the embedded H2 driver).
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
-- `MERGE INTO ... KEY (user_id)` makes the seed idempotent — running this
-- script after the rows already exist updates them in place rather than
-- failing on the PK constraint, so application.yml safely runs it on every
-- startup (`spring.sql.init.mode=always`).
--
-- These accounts are scoped to H2 by `spring.sql.init.platform=h2`. The
-- `jdbc` Spring profile (application-jdbc.yml) flips
-- `spring.sql.init.mode=never`, so MySQL / PostgreSQL deployments never run
-- this script.
-- ---------------------------------------------------------------------------

MERGE INTO dataagent_user (user_id, username, password_hash, roles_csv, created_at) KEY (user_id) VALUES
  ('bob',   'bob',   '$2y$10$jlcRmUapzgu.P95uby8yx.1KSxQe.7Hmj0.fDJlg4OAOgfMruaU2y', 'user', CURRENT_TIMESTAMP),
  ('alice', 'alice', '$2y$10$KO33XwJbFTsFGTPIlrvLe.xvI0tK3PNKVNVGpHNQyMZhzDxVdoKA6', 'user', CURRENT_TIMESTAMP);

-- ---------------------------------------------------------------------------
-- Demo analytics table for the `demo-db` data source (H2 deployments only —
-- application-jdbc.yml sets spring.sql.init.mode=never so external RDBMS
-- deployments never see this).
--
-- One wide fact table the agent can answer real business questions against:
-- orders across 4 regions, 5 categories, 3 channels over the whole of 2025,
-- with a deliberate year-end seasonal uplift and ~4% refunded orders so
-- monthly-trend / category-comparison / channel-mix questions all have an
-- interesting answer.
--
-- Deterministic + idempotent: rebuilt from SYSTEM_RANGE(1, 600) with pure
-- modular arithmetic on every startup, so no RANDOM() and no drift between
-- runs. MOD(X * 61, 365) is coprime with 365, spreading the 600 orders
-- uniformly across the year.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS demo_orders (
  order_id   INT            NOT NULL,
  order_date DATE           NOT NULL,
  region     VARCHAR(16)    NOT NULL,
  category   VARCHAR(24)    NOT NULL,
  channel    VARCHAR(12)    NOT NULL,
  quantity   INT            NOT NULL,
  amount     DECIMAL(10, 2) NOT NULL,
  status     VARCHAR(12)    NOT NULL,
  PRIMARY KEY (order_id)
);

DELETE FROM demo_orders;

INSERT INTO demo_orders (order_id, order_date, region, category, channel, quantity, amount, status)
SELECT
  X,
  DATEADD('DAY', MOD(X * 61, 365), DATE '2025-01-01'),
  CASE MOD(X, 4) WHEN 0 THEN 'East' WHEN 1 THEN 'South' WHEN 2 THEN 'West' ELSE 'North' END,
  CASE MOD(X, 5) WHEN 0 THEN 'Electronics' WHEN 1 THEN 'Apparel' WHEN 2 THEN 'Home' WHEN 3 THEN 'Beauty' ELSE 'Sports' END,
  CASE MOD(X, 3) WHEN 0 THEN 'web' WHEN 1 THEN 'app' ELSE 'store' END,
  MOD(X, 9) + 1,
  CAST(
    (MOD(X, 9) + 1)
    * CASE MOD(X, 5)
        WHEN 0 THEN 420.0
        WHEN 1 THEN 89.0
        WHEN 2 THEN 156.0
        WHEN 3 THEN 45.0
        ELSE 67.0
      END
    * (0.85 + 0.05 * CAST(MOD(X * 61, 365) / 31 AS INT))
    * (1.0 + MOD(X * 17, 7) / 100.0)
    AS DECIMAL(10, 2)),
  CASE WHEN MOD(X, 25) = 0 THEN 'refunded' ELSE 'completed' END
FROM SYSTEM_RANGE(1, 600);
