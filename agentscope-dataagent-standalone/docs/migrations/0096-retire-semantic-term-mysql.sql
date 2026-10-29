-- spec095 / ADR0115: business terms now live in each knowledge base's Wren project as the official
-- "## Naming conventions" of knowledge/rules/naming.md. The platform term table is retired and its
-- rows are not migrated (user decision, 2026-10-29). Apply once after deploying; with ddl-auto=update
-- Hibernate leaves the orphan table in place, so drop it explicitly.
DROP TABLE IF EXISTS dataagent_semantic_term;
