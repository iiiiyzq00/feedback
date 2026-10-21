UPDATE t_alert_rule_catalog
SET implementation_status='IMPLEMENTED'
WHERE rule_code='INGEST_PROCESSING_FAILED';

INSERT INTO t_alert_rule(rule_code,rule_name,category,scope_type,owner_team,version,version_status,
  default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'INGEST_PROCESSING_FAILED','接入信封处理失败','DATA_INGEST','INGEST','数据平台','v1','ACTIVE',
  'P2',JSON_OBJECT('mode','EVENT','metric','EVENT_ONLY'),NULL,
  JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'escalation','30M_TO_P1'),30,1
WHERE NOT EXISTS (
  SELECT 1 FROM t_alert_rule
  WHERE rule_code='INGEST_PROCESSING_FAILED' AND version_status='ACTIVE'
);
