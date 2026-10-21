INSERT INTO t_system_config(
  config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,owner_team,config_subtype,record_status,
  config_desc,is_active,created_at,updated_at)
VALUES('AUDIT_ACTION:FEEDBACK_DIAGNOSTICS_VIEW','AUDIT_ACTION_CATALOG','v1',
  JSON_OBJECT('sensitiveAction',TRUE),'audit-action-v1','ACTIVE','APPROVED',
  'SECURITY_GOVERNANCE','SECURITY','ACTIVE','受控查看反馈诊断身份与日志',1,NOW(3),NOW(3))
ON DUPLICATE KEY UPDATE config_type=VALUES(config_type),config_value=VALUES(config_value),
  config_schema_version=VALUES(config_schema_version),lifecycle_status=VALUES(lifecycle_status),
  approval_status=VALUES(approval_status),owner_team=VALUES(owner_team),config_subtype=VALUES(config_subtype),
  record_status=VALUES(record_status),config_desc=VALUES(config_desc),is_active=1,updated_at=NOW(3);
