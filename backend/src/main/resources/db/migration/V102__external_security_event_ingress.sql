CREATE TABLE t_external_security_event_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider_code VARCHAR(100) NOT NULL,
  external_event_id VARCHAR(100) NOT NULL,
  rule_code VARCHAR(80) NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  scope_hash CHAR(64) NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  evidence_snapshot JSON NOT NULL,
  alert_instance_id BIGINT NOT NULL,
  incident_id BIGINT NOT NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_external_security_provider_event(provider_code,external_event_id),
  KEY idx_external_security_rule_time(rule_code,occurred_at),
  CONSTRAINT chk_external_security_rule CHECK(rule_code IN ('UNAUTHORIZED_ACCESS_DETECTED','KEY_MATERIAL_EXPOSURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='SIEM和密钥扫描器鉴权事件接入回执';

UPDATE t_alert_data_contract SET schema_version='external-security-event-v1',source_system='HMAC_WEBHOOK',freshness_seconds=60,completeness_threshold=.999000
WHERE contract_code IN ('SIEM','audit_event','secret_scanner') AND contract_status='DRAFT';
