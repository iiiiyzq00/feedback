-- Generated columns used to emulate partial unique indexes in MySQL.

ALTER TABLE t_import_template ADD COLUMN active_key VARCHAR(200) GENERATED ALWAYS AS (CASE WHEN is_active THEN CONCAT(source_channel, ':', template_name) ELSE NULL END) STORED;
ALTER TABLE t_cluster_link ADD COLUMN active_feedback_source_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_active THEN feedback_source_id ELSE NULL END) STORED;
ALTER TABLE t_ai_task ADD COLUMN current_prompt_key VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN record_type='PROMPT_VERSION' AND is_current THEN skill_code ELSE NULL END) STORED;
ALTER TABLE t_alert_rule ADD COLUMN active_rule_key VARCHAR(50) GENERATED ALWAYS AS (CASE WHEN version_status='ACTIVE' THEN rule_code ELSE NULL END) STORED;
ALTER TABLE t_system_config ADD COLUMN active_config_key VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN is_active THEN config_key ELSE NULL END) STORED;
