-- Fresh installation only: select an EMPTY platform database before execution.
-- Baseline: merged candidate (f1ad7772 + b67a179a), originally verified with ddl-auto=validate.
-- Updated 2026-10-30: recommended-question columns; legacy semantic-term table retained.
-- Updated 2026-10-30: five nullable onboarding columns on dataagent_user (spec104).
-- Fresh/upgrade DDL equivalence verified on isolated MySQL 8.0.46.
-- Structure only: no user rows, business data or credentials; upload database stays empty.
-- Do not run incremental migrations on top of this complete schema.

SET @dataagent_previous_fk_checks = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `activity_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_user_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `actor_username` varchar(191) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `agent_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `metadata_json` text COLLATE utf8mb4_unicode_ci,
  `owner_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `timestamp_ms` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKj5gd52iwvmcmvf9y7vf4yi6b3` (`event_id`),
  KEY `ix_activity_event_owner_agent_ts` (`owner_id`,`agent_id`,`timestamp_ms`),
  KEY `ix_activity_event_actor_user_id` (`actor_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_agent` (
  `row_id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` bigint DEFAULT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `fork_of` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `group_chat_mention_patterns_json` text COLLATE utf8mb4_unicode_ci,
  `group_chat_require_mention` bit(1) DEFAULT NULL,
  `identity_emoji` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `identity_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `max_iters` int DEFAULT NULL,
  `model` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `owner_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `run_as` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sandbox_mode` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sandbox_scope` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `skill_repositories_json` text COLLATE utf8mb4_unicode_ci,
  `skills_allow_json` text COLLATE utf8mb4_unicode_ci,
  `skills_deny_json` text COLLATE utf8mb4_unicode_ci,
  `sys_prompt` text COLLATE utf8mb4_unicode_ci,
  `tools_allow_json` text COLLATE utf8mb4_unicode_ci,
  `tools_deny_json` text COLLATE utf8mb4_unicode_ci,
  `updated_at` bigint DEFAULT NULL,
  `workspace_path` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`row_id`),
  UNIQUE KEY `ux_dataagent_agent_owner_agent` (`owner_id`,`agent_id`),
  KEY `ix_dataagent_agent_owner` (`owner_id`),
  KEY `ix_dataagent_agent_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_agent_share` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` bigint DEFAULT NULL,
  `created_by` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `grantee_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `grantee_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `tier` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agent_row_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_agent_share_grantee` (`grantee_type`,`grantee_id`),
  KEY `fk_share_agent` (`agent_row_id`),
  CONSTRAINT `fk_share_agent` FOREIGN KEY (`agent_row_id`) REFERENCES `dataagent_agent` (`row_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_agent_state` (
  `session_id` varchar(255) COLLATE utf8mb4_bin NOT NULL,
  `state_key` varchar(255) COLLATE utf8mb4_bin NOT NULL,
  `item_index` int NOT NULL DEFAULT '0',
  `state_data` longtext COLLATE utf8mb4_bin NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`session_id`,`state_key`,`item_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `dataagent_bi_login_ticket` (
  `request_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`request_id`),
  KEY `ix_bi_ticket_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_chart_option` (
  `chart_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `option_json` text COLLATE utf8mb4_unicode_ci,
  `owner_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`chart_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_contribution` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `approved_payload` text COLLATE utf8mb4_unicode_ci,
  `created_at` bigint NOT NULL,
  `payload` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `rationale` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reviewer_note` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reviewer_user_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_agent_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_user_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_agent_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_path` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_dataagent_contribution_status` (`status`),
  KEY `ix_dataagent_contribution_source_user` (`source_user_id`),
  KEY `ix_dataagent_contribution_target` (`target_agent_id`,`target_type`,`target_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_conversation_dataset_scope` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `groups_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `selection_json` text COLLATE utf8mb4_unicode_ci,
  `session_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_dataset` (
  `dataset_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `column_schema_json` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `external_datasource_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `field_screening_confirmed` bit(1) DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(191) COLLATE utf8mb4_unicode_ci NOT NULL,
  `origin` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `owner_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `row_count` bigint DEFAULT NULL,
  `sample_summary_json` longtext COLLATE utf8mb4_unicode_ci,
  `schema_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_file_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `table_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`dataset_id`),
  UNIQUE KEY `ix_dataagent_dataset_owner_group_name` (`owner_id`,`group_id`,`name`),
  KEY `ix_dataagent_dataset_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_dataset_group` (
  `public_query` bit(1) NOT NULL DEFAULT b'0',
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fact_query_window_days` int DEFAULT NULL,
  `mdl_last_error` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `mdl_published_at` datetime(6) DEFAULT NULL,
  `recommended_question` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `recommended_question_manual` bit(1) DEFAULT NULL,
  `mdl_state` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `mdl_version` int DEFAULT NULL,
  `modeling_revision` bigint DEFAULT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `published_modeling_revision` bigint DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`group_id`),
  UNIQUE KEY `ix_dataagent_group_owner_name` (`owner_id`,`name`),
  KEY `ix_dataagent_group_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_dataset_relation` (
  `relation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `confidence` double NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `join_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `origin` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `relation_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_column` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_columns` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_dataset_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `target_column` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `target_columns` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `target_dataset_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`relation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_doc_enhance_proposal` (
  `proposal_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `classification` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `confidence` varchar(8) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `decided_at` datetime(6) DEFAULT NULL,
  `decision_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fingerprint` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload_json` varchar(12000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `proposal_type` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_quote` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `summary` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `task_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`proposal_id`),
  KEY `ix_doc_enhance_proposal_task` (`task_id`),
  KEY `ix_doc_enhance_proposal_owner_group` (`owner_id`,`group_id`),
  KEY `ix_doc_enhance_proposal_group_status` (`group_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_doc_enhance_task` (
  `task_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `error_message` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_label` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`task_id`),
  KEY `ix_doc_enhance_task_owner_group` (`owner_id`,`group_id`),
  KEY `ix_doc_enhance_task_group_status` (`group_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_external_datasource` (
  `datasource_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `jdbc_url` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
  `kind` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sampling` bit(1) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `username` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_fact_query_window` (
  `window_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `dataset_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `table_name` varchar(191) COLLATE utf8mb4_unicode_ci NOT NULL,
  `temporal_columns` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `updated_by` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `window_days` int DEFAULT NULL,
  PRIMARY KEY (`window_id`),
  UNIQUE KEY `ux_fact_window_dataset` (`group_id`,`dataset_id`),
  KEY `ix_fact_window_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_group_knowledge` (
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` text COLLATE utf8mb4_unicode_ci,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_kg_build_task` (
  `task_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `triggered_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_kg_build_unit` (
  `unit_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `error_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload` text COLLATE utf8mb4_unicode_ci,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `unit_name` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `unit_ref_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `unit_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`unit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_kg_entity` (
  `entity_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attributes_json` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `label` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `normalized_key` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `text` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`entity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_kg_relation` (
  `relation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `label` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_entity_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_entity_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `text` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`relation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_knowledge_base_grant` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `granted_by` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ix_kb_grant_group_user` (`group_id`,`user_id`),
  KEY `ix_kb_grant_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_knowledge_base_grant_audit` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `new_role` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `previous_role` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_kb_grant_audit_group` (`group_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_model_service_config` (
  `config_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `encrypted_payload` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_scheduled_task` (
  `task_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agent_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `created_by` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `effective_from` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `knowledge_base_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt` varchar(5000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `run_count` int DEFAULT NULL,
  `schedule_frequency` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `schedule_time` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_semantic_business_rule` (
  `rule_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(2000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `owner_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_label` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`rule_id`),
  UNIQUE KEY `ix_semantic_rule_group_name` (`group_id`,`name`),
  KEY `ix_semantic_rule_owner_group` (`owner_id`,`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_semantic_cube` (
  `cube_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `base_dataset_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `dimensions_json` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `measures_json` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `time_dimensions_json` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`cube_id`),
  UNIQUE KEY `ix_dataagent_cube_group_name` (`group_id`,`name`),
  KEY `ix_dataagent_cube_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_semantic_term` (
  `term_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `explanation` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `scope` varchar(191) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `synonyms` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `term` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`term_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_semantic_view` (
  `view_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `base_dataset_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `group_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sql_text` varchar(4000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`view_id`),
  UNIQUE KEY `ix_dataagent_view_group_name` (`group_id`,`name`),
  KEY `ix_dataagent_view_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_user` (
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `onboarding_started_at` datetime(6) DEFAULT NULL,
  `onboarding_dismissed_at` datetime(6) DEFAULT NULL,
  `onboarding_completed_at` datetime(6) DEFAULT NULL,
  `onboarding_milestones` varchar(200) DEFAULT NULL,
  `onboarding_tips_seen` varchar(200) DEFAULT NULL,
  `onboarding_tour` varchar(200) DEFAULT NULL,
  `onboarding_reset_at` datetime(6) DEFAULT NULL,
  `display_name` varchar(191) DEFAULT NULL,
  `company` varchar(191) DEFAULT NULL,
  `department` varchar(191) DEFAULT NULL,
  `bindings_json` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `password_hash` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `roles_csv` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `username` varchar(191) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `ix_dataagent_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `dataagent_user_marketplace` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `marketplace_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `properties_json` text COLLATE utf8mb4_unicode_ci,
  `type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_marketplace` (`user_id`,`marketplace_id`),
  KEY `ix_user_marketplace_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `identity_link` (
  `channel_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `external_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`channel_id`,`user_id`),
  KEY `ix_identity_link_user_id` (`user_id`),
  KEY `ix_identity_link_channel_external` (`channel_id`,`external_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `session_artifact` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at_ms` bigint NOT NULL,
  `filename` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `input_data` bit(1) NOT NULL,
  `owner_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `size_bytes` bigint NOT NULL,
  `source_path` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_artifact_session` (`owner_id`,`session_id`),
  KEY `ix_artifact_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `session_history` (
  `session_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `activity_ms` bigint NOT NULL,
  `file_size` bigint NOT NULL,
  `file_stamp` bigint NOT NULL,
  `message_count` int NOT NULL,
  `preview` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `session_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_path` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `title` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`session_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `session_message` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `position_no` int NOT NULL,
  `session_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ix_message_order` (`session_key`,`position_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `session_read_state` (
  `session_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_read_at_ms` bigint NOT NULL,
  PRIMARY KEY (`session_key`,`user_id`),
  KEY `ix_session_read_state_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `session_registry` (
  `session_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agent_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at_ms` bigint DEFAULT NULL,
  `gate_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `kind` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `label` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_activity_ms` bigint DEFAULT NULL,
  `session_file_path` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `session_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `spawn_depth` int DEFAULT NULL,
  `spawn_run_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `spawned_by` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`session_key`),
  KEY `ix_session_registry_agent_id` (`agent_id`),
  KEY `ix_session_registry_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `usage_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `duration_ms` bigint NOT NULL,
  `timestamp_ms` bigint NOT NULL,
  `user_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_usage_event_ts` (`timestamp_ms`),
  KEY `ix_usage_event_user_ts` (`user_id`,`timestamp_ms`),
  KEY `ix_usage_event_agent_ts` (`agent_id`,`timestamp_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = @dataagent_previous_fk_checks;
