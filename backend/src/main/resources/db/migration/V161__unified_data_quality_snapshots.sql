-- Keep the governed 42-table model: batch facts stay on t_import_batch and
-- channel snapshots reuse t_alert_occurrence.
ALTER TABLE t_import_batch
  ADD COLUMN quality_time_valid_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_invalid_rate,
  ADD COLUMN quality_enum_valid_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_time_valid_rate,
  ADD COLUMN quality_app_mapping_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_enum_valid_rate,
  ADD COLUMN quality_module_mapping_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_app_mapping_rate,
  ADD COLUMN quality_version_mapping_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_module_mapping_rate,
  ADD COLUMN quality_import_success_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_version_mapping_rate,
  ADD COLUMN quality_cross_channel_duplicate_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_import_success_rate,
  ADD COLUMN quality_anomaly_count BIGINT DEFAULT NULL AFTER quality_cross_channel_duplicate_rate,
  ADD COLUMN quality_quarantine_count BIGINT DEFAULT NULL AFTER quality_anomaly_count;

INSERT INTO t_system_config(config_type,config_key,config_version,config_value,config_schema_version,lifecycle_status,approval_status,owner_team,config_desc,is_active)
VALUES('DATA_QUALITY','data-quality.thresholds','data-quality-v1',JSON_OBJECT(
  'requiredCompletenessMin',0.95,'primaryKeyDuplicateMax',0.05,'timeValidMin',0.98,
  'enumValidMin',0.95,'mappingMin',0.90,'maskingPassMin',0.99,
  'importSuccessMin',0.90,'crossChannelDuplicateMax',0.30,'quarantineMax',0),
  'data-quality-threshold-v1','ACTIVE','APPROVED','数据平台','按渠道和批次的数据质量告警阈值',1)
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),updated_at=NOW(3);

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,
  trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'DATA_QUALITY_THRESHOLD_BREACH','渠道或批次数据质量超过阈值','DATA_QUALITY','数据平台+渠道负责人',175,
  'quality-v1','ACTIVE','P2',JSON_OBJECT('mode','EVENT'),JSON_OBJECT('manualClosureRequired',FALSE),
  JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','PRODUCTION'),30,1
WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='DATA_QUALITY_THRESHOLD_BREACH' AND version_status='ACTIVE');
