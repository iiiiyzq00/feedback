-- Phase 1 of the 40-table consolidation.
-- Additive only: no legacy table is renamed or dropped in this migration.

ALTER TABLE t_system_config
  ADD COLUMN config_type VARCHAR(50) NOT NULL DEFAULT 'SYSTEM' AFTER config_key,
  ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER config_schema_version,
  ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' AFTER lifecycle_status,
  ADD COLUMN owner_team VARCHAR(100) DEFAULT NULL AFTER approval_status,
  ADD COLUMN effective_from DATETIME(3) DEFAULT NULL AFTER owner_team,
  ADD COLUMN effective_to DATETIME(3) DEFAULT NULL AFTER effective_from,
  ADD KEY idx_config_type_active (config_type,is_active,config_key),
  ADD KEY idx_config_lifecycle (config_type,lifecycle_status,approval_status),
  ADD KEY idx_config_effective (config_type,effective_from,effective_to);

ALTER TABLE t_ai_task
  MODIFY COLUMN record_type VARCHAR(50) NOT NULL,
  ADD COLUMN task_domain VARCHAR(30) DEFAULT NULL AFTER record_type,
  ADD COLUMN parent_task_id BIGINT DEFAULT NULL AFTER source_id,
  ADD COLUMN task_sequence INT DEFAULT NULL AFTER parent_task_id,
  ADD COLUMN correlation_key VARCHAR(200) DEFAULT NULL AFTER task_key,
  ADD KEY idx_unified_task_claim (task_domain,record_type,task_status,next_retry_at,created_at,id),
  ADD KEY idx_unified_task_parent (parent_task_id,task_sequence,id),
  ADD KEY idx_unified_task_correlation (task_domain,correlation_key);

ALTER TABLE t_system_audit_log
  ADD COLUMN correlation_id VARCHAR(100) DEFAULT NULL AFTER request_id,
  ADD COLUMN event_time DATETIME(3) DEFAULT NULL AFTER ip_address,
  ADD KEY idx_audit_correlation (correlation_id,created_at,id),
  ADD KEY idx_audit_event_time (audit_class,operation,event_time,id);

ALTER TABLE t_alert_occurrence
  ADD COLUMN occurrence_type VARCHAR(30) NOT NULL DEFAULT 'RULE_EVALUATION' AFTER instance_id,
  ADD COLUMN metric_code VARCHAR(100) DEFAULT NULL AFTER occurrence_type,
  ADD COLUMN metric_value DECIMAL(20,6) DEFAULT NULL AFTER metric_code,
  ADD COLUMN dimension_key VARCHAR(200) DEFAULT NULL AFTER metric_value,
  ADD KEY idx_occurrence_type_time (occurrence_type,evaluation_time,id),
  ADD KEY idx_occurrence_metric_time (metric_code,evaluation_time,id),
  ADD KEY idx_occurrence_dimension_time (dimension_key,evaluation_time,id);

ALTER TABLE t_import_batch
  ADD COLUMN quality_snapshot JSON DEFAULT NULL AFTER reconciliation_status;

ALTER TABLE t_import_row
  ADD COLUMN normalization_history JSON DEFAULT NULL AFTER quality_suggestion;
