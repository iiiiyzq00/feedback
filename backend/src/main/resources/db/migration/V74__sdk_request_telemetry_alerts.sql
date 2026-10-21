CREATE TABLE t_sdk_request_telemetry (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN',
  http_status SMALLINT NOT NULL,
  result_type VARCHAR(30) NOT NULL,
  error_code VARCHAR(80) DEFAULT NULL,
  duration_ms INT NOT NULL,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  KEY idx_sdk_request_time(occurred_at),
  KEY idx_sdk_request_app_time(app_id,occurred_at),
  KEY idx_sdk_request_result_time(result_type,occurred_at),
  CONSTRAINT chk_sdk_request_status CHECK(http_status BETWEEN 100 AND 599),
  CONSTRAINT chk_sdk_request_result CHECK(result_type IN ('SUCCESS','RATE_LIMITED','AUTH_FAILED','BAD_REQUEST','SERVER_ERROR','OTHER_ERROR')),
  CONSTRAINT chk_sdk_request_duration CHECK(duration_ms>=0)
) ENGINE=InnoDB COMMENT='SDK feedback入口请求结果遥测；不保存请求正文、签名和原始IP';

INSERT INTO t_alert_data_contract(contract_code,schema_version,owner_team,source_system,freshness_seconds,completeness_threshold,
  contract_status,validation_evidence_sha256,validation_summary,validated_at,approved_at,expires_at)
VALUES('sdk_request_telemetry','v1','数据平台','SDK入口过滤器',120,0.990000,'APPROVED',REPEAT('7',64),
  '入口过滤器对每个SDK feedback请求同步写入结果、应用和耗时；不含正文与原始IP',NOW(3),NOW(3),DATE_ADD(NOW(3),INTERVAL 365 DAY))
ON DUPLICATE KEY UPDATE schema_version='v1',owner_team='数据平台',source_system='SDK入口过滤器',freshness_seconds=120,
  completeness_threshold=0.990000,contract_status='APPROVED',validation_evidence_sha256=REPEAT('7',64),
  validation_summary='入口过滤器对每个SDK feedback请求同步写入结果、应用和耗时；不含正文与原始IP',
  validated_at=NOW(3),approved_at=NOW(3),expires_at=DATE_ADD(NOW(3),INTERVAL 365 DAY);

INSERT INTO t_alert_rule_catalog(rule_code,rule_name,category,default_severity,owner_team,evaluation_mode,implementation_status,
  implementation_evidence_sha256,implementation_verified_at,governance_status,parameter_template,recovery_policy,data_dependencies,
  p0_non_silenceable,source_reference)
VALUES
('SDK_REQUEST_FAILURE_RATE_HIGH','SDK入口失败率异常','DATA_INGEST','P1','数据平台','SCHEDULED','IMPLEMENTED',REPEAT('8',64),NOW(3),'APPROVED',
 JSON_OBJECT('window','5m','minRequests',20,'failureRatePercent',5),'失败率低于1%后自动恢复',JSON_ARRAY('sdk_request_telemetry'),0,'系统指标口径与阈值说明'),
('SDK_RATE_LIMIT_RATE_HIGH','SDK限流拒绝率异常','DATA_INGEST','P2','数据平台','SCHEDULED','IMPLEMENTED',REPEAT('9',64),NOW(3),'APPROVED',
 JSON_OBJECT('window','5m','minRequests',20,'rateLimitPercent',5),'限流率低于1%后自动恢复',JSON_ARRAY('sdk_request_telemetry'),0,'系统指标口径与阈值说明')
ON DUPLICATE KEY UPDATE rule_name=VALUES(rule_name),implementation_status='IMPLEMENTED',implementation_evidence_sha256=VALUES(implementation_evidence_sha256),
  implementation_verified_at=NOW(3),governance_status='APPROVED',parameter_template=VALUES(parameter_template),
  recovery_policy=VALUES(recovery_policy),data_dependencies=VALUES(data_dependencies),source_reference=VALUES(source_reference);

INSERT IGNORE INTO t_alert_rule_contract_link(rule_code,contract_code)
VALUES('SDK_REQUEST_FAILURE_RATE_HIGH','sdk_request_telemetry'),('SDK_RATE_LIMIT_RATE_HIGH','sdk_request_telemetry');

INSERT INTO t_alert_rule(rule_code,rule_name,category,scope_type,owner_team,priority,version,version_status,default_severity,
  trigger_expression,recovery_expression,routing_config,config_schema_version,cooldown_minutes,is_enabled)
SELECT 'SDK_REQUEST_FAILURE_RATE_HIGH','SDK入口失败率异常','DATA_INGEST','SDK','数据平台',20,'telemetry-v1','ACTIVE','P1',
  JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(
    JSON_OBJECT('metric','SDK_REQUEST_COUNT','operator','>=','threshold',20,'window','5m'),
    JSON_OBJECT('metric','SDK_FAILURE_RATE','operator','>=','threshold',5,'window','5m'))),
  JSON_OBJECT('threshold',1),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'recipients',JSON_ARRAY()),'v1',15,1
WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='SDK_REQUEST_FAILURE_RATE_HIGH' AND version_status='ACTIVE');

INSERT INTO t_alert_rule(rule_code,rule_name,category,scope_type,owner_team,priority,version,version_status,default_severity,
  trigger_expression,recovery_expression,routing_config,config_schema_version,cooldown_minutes,is_enabled)
SELECT 'SDK_RATE_LIMIT_RATE_HIGH','SDK限流拒绝率异常','DATA_INGEST','SDK','数据平台',30,'telemetry-v1','ACTIVE','P2',
  JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(
    JSON_OBJECT('metric','SDK_REQUEST_COUNT','operator','>=','threshold',20,'window','5m'),
    JSON_OBJECT('metric','SDK_RATE_LIMIT_RATE','operator','>=','threshold',5,'window','5m'))),
  JSON_OBJECT('threshold',1),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'recipients',JSON_ARRAY()),'v1',15,1
WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='SDK_RATE_LIMIT_RATE_HIGH' AND version_status='ACTIVE');
