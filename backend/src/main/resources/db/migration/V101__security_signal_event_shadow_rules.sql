-- Deterministic, redacted security facts. P0 rules remain SHADOW and disabled;
-- ordinary silence is still prohibited by the authoritative catalog.
CREATE TABLE t_security_signal_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  signal_type VARCHAR(50) NOT NULL,
  evidence_level VARCHAR(20) NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  source_type VARCHAR(30) NOT NULL,
  source_ref_hash CHAR(64) NOT NULL,
  evidence_snapshot JSON NOT NULL,
  signal_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  concluded_by BIGINT DEFAULT NULL,
  concluded_at DATETIME(3) DEFAULT NULL,
  conclusion VARCHAR(500) DEFAULT NULL,
  UNIQUE KEY uk_security_signal_fact (signal_type,source_type,source_ref_hash,evidence_level),
  KEY idx_security_signal_open (signal_type,signal_status,occurred_at),
  CONSTRAINT chk_security_signal_type CHECK(signal_type IN ('DEVICE_SAFETY','SENSITIVE_RESIDUAL_BLOCKED','SENSITIVE_EXPOSURE_CONFIRMED')),
  CONSTRAINT chk_security_evidence_level CHECK(evidence_level IN ('SUSPECTED','STRONG','CONFIRMED')),
  CONSTRAINT chk_security_signal_risk CHECK(risk_level IN ('MEDIUM','HIGH','CRITICAL')),
  CONSTRAINT chk_security_signal_status CHECK(signal_status IN ('OPEN','MITIGATED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='无明文、不可逆引用的安全告警事实';

UPDATE t_alert_data_contract SET schema_version='security-signal-event-v1',source_system='t_security_signal_event',freshness_seconds=60,completeness_threshold=.999000
WHERE contract_code IN ('safety_classification','evidence_level','evidence_snapshot','masking_scan_event','risk_level','exposure_audit_event') AND contract_status='DRAFT';

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'DEVICE_SAFETY_SUSPECTED','设备安全疑似','SAFETY','硬件质量+安全',390,'shadow-v1','SHADOW','P1',JSON_OBJECT('mode','EVENT','conditions',JSON_ARRAY(JSON_OBJECT('metric','OPEN_DEVICE_SAFETY_SUSPECTED_COUNT','operator','>=','threshold',1,'window','24h'))),JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),30,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='DEVICE_SAFETY_SUSPECTED' AND version_status='SHADOW');
INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'DEVICE_SAFETY_STRONG_EVIDENCE','设备安全强证据','SAFETY','硬件质量+安全',400,'shadow-v1','SHADOW','P0',JSON_OBJECT('mode','EVENT','conditions',JSON_ARRAY(JSON_OBJECT('metric','OPEN_DEVICE_SAFETY_STRONG_COUNT','operator','>=','threshold',1,'window','24h'))),JSON_OBJECT('manualClosureRequired',true),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED','ordinarySilenceAllowed',false),0,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='DEVICE_SAFETY_STRONG_EVIDENCE' AND version_status='SHADOW');
INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'SENSITIVE_DATA_RESIDUAL_BLOCKED','敏感数据残留被阻断','SAFETY','隐私+数据平台',410,'shadow-v1','SHADOW','P1',JSON_OBJECT('mode','EVENT','conditions',JSON_ARRAY(JSON_OBJECT('metric','OPEN_SENSITIVE_RESIDUAL_COUNT','operator','>=','threshold',1,'window','24h'))),JSON_OBJECT('threshold',0,'safeDestructionRequired',true),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='SENSITIVE_DATA_RESIDUAL_BLOCKED' AND version_status='SHADOW');
INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'SENSITIVE_DATA_EXPOSURE_CONFIRMED','敏感数据暴露确认','SAFETY','隐私+安全',420,'shadow-v1','SHADOW','P0',JSON_OBJECT('mode','EVENT','conditions',JSON_ARRAY(JSON_OBJECT('metric','OPEN_SENSITIVE_EXPOSURE_COUNT','operator','>=','threshold',1,'window','24h'))),JSON_OBJECT('manualClosureRequired',true),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED','ordinarySilenceAllowed',false),0,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='SENSITIVE_DATA_EXPOSURE_CONFIRMED' AND version_status='SHADOW');
