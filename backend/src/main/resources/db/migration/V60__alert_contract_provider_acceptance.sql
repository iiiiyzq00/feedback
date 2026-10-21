CREATE TABLE t_alert_data_contract (
  contract_code VARCHAR(100) NOT NULL,
  schema_version VARCHAR(50) NOT NULL,
  owner_team VARCHAR(100) NOT NULL,
  source_system VARCHAR(100) NOT NULL,
  freshness_seconds INT NOT NULL,
  completeness_threshold DECIMAL(7,6) NOT NULL,
  contract_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  validation_evidence_sha256 CHAR(64) DEFAULT NULL,
  validation_summary VARCHAR(500) DEFAULT NULL,
  validated_by BIGINT DEFAULT NULL,
  validated_at DATETIME(3) DEFAULT NULL,
  approved_by BIGINT DEFAULT NULL,
  approved_at DATETIME(3) DEFAULT NULL,
  expires_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(contract_code),
  CONSTRAINT fk_alert_contract_validator FOREIGN KEY(validated_by) REFERENCES t_auth_user(id),
  CONSTRAINT fk_alert_contract_approver FOREIGN KEY(approved_by) REFERENCES t_auth_user(id),
  CONSTRAINT chk_alert_contract_status CHECK(contract_status IN ('DRAFT','VALIDATED','APPROVED','SUSPENDED','EXPIRED')),
  CONSTRAINT chk_alert_contract_quality CHECK(freshness_seconds>0 AND completeness_threshold BETWEEN 0.900000 AND 1.000000)
) ENGINE=InnoDB COMMENT='告警规则输入数据契约；验证人与审批人必须不同';

CREATE TABLE t_alert_rule_contract_link (
  rule_code VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  contract_code VARCHAR(100) NOT NULL,
  required_flag TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY(rule_code,contract_code),
  CONSTRAINT fk_rule_contract_rule FOREIGN KEY(rule_code) REFERENCES t_alert_rule_catalog(rule_code),
  CONSTRAINT fk_rule_contract_contract FOREIGN KEY(contract_code) REFERENCES t_alert_data_contract(contract_code)
) ENGINE=InnoDB COMMENT='正式规则到输入契约的强制依赖';

INSERT INTO t_alert_data_contract(contract_code,schema_version,owner_team,source_system,freshness_seconds,completeness_threshold)
SELECT dep.contract_code,'unapproved-v1',
       CASE WHEN COUNT(DISTINCT c.owner_team)=1 THEN MIN(c.owner_team) ELSE 'MULTI_TEAM' END,
       'UNASSIGNED',300,0.950000
FROM t_alert_rule_catalog c
JOIN JSON_TABLE(c.data_dependencies,'$[*]' COLUMNS(contract_code VARCHAR(100) PATH '$')) dep
GROUP BY dep.contract_code;

INSERT INTO t_alert_rule_contract_link(rule_code,contract_code)
SELECT c.rule_code,dep.contract_code FROM t_alert_rule_catalog c
JOIN JSON_TABLE(c.data_dependencies,'$[*]' COLUMNS(contract_code VARCHAR(100) PATH '$')) dep;

ALTER TABLE t_alert_rule_catalog
  ADD COLUMN implementation_evidence_sha256 CHAR(64) DEFAULT NULL AFTER implementation_status,
  ADD COLUMN implementation_verified_by BIGINT DEFAULT NULL AFTER implementation_evidence_sha256,
  ADD COLUMN implementation_verified_at DATETIME(3) DEFAULT NULL AFTER implementation_verified_by,
  ADD CONSTRAINT fk_alert_catalog_impl_verifier FOREIGN KEY(implementation_verified_by) REFERENCES t_auth_user(id);

CREATE TABLE t_notification_provider_acceptance (
  id BIGINT NOT NULL AUTO_INCREMENT,
  notify_channel VARCHAR(30) NOT NULL,
  provider_code VARCHAR(100) NOT NULL,
  environment VARCHAR(20) NOT NULL,
  acceptance_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  capability_snapshot JSON NOT NULL,
  daily_quota BIGINT NOT NULL,
  idempotency_supported TINYINT(1) NOT NULL,
  evidence_sha256 CHAR(64) DEFAULT NULL,
  evidence_reference VARCHAR(500) DEFAULT NULL,
  validated_by BIGINT DEFAULT NULL,
  validated_at DATETIME(3) DEFAULT NULL,
  approved_by BIGINT DEFAULT NULL,
  approved_at DATETIME(3) DEFAULT NULL,
  expires_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_provider_acceptance(notify_channel,provider_code,environment),
  KEY idx_provider_acceptance_gate(notify_channel,environment,acceptance_status,expires_at),
  CONSTRAINT fk_provider_validator FOREIGN KEY(validated_by) REFERENCES t_auth_user(id),
  CONSTRAINT fk_provider_approver FOREIGN KEY(approved_by) REFERENCES t_auth_user(id),
  CONSTRAINT chk_provider_channel CHECK(notify_channel IN ('FEISHU_CARD','FEISHU_ALL','FEISHU_DM','FEISHU_TASK','SMS','VOICE_CALL','EMAIL','IN_APP')),
  CONSTRAINT chk_provider_environment CHECK(environment IN ('TEST','STAGING','PRODUCTION')),
  CONSTRAINT chk_provider_status CHECK(acceptance_status IN ('DRAFT','VALIDATED','APPROVED','SUSPENDED','EXPIRED')),
  CONSTRAINT chk_provider_quota CHECK(daily_quota>=0)
) ENGINE=InnoDB COMMENT='通知供应商环境级准入、配额、幂等、回执与DTMF证据';

INSERT INTO t_notification_provider_acceptance(notify_channel,provider_code,environment,acceptance_status,capability_snapshot,daily_quota,idempotency_supported,evidence_sha256,evidence_reference,validated_at,approved_at)
VALUES('IN_APP','PLATFORM','PRODUCTION','APPROVED',JSON_OBJECT('accepted',true,'localDelivery',true),9223372036854775807,1,REPEAT('0',64),'内建站内通知，不依赖外部供应商',NOW(3),NOW(3));

CREATE TABLE t_notification_provider_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  callback_id VARCHAR(100) NOT NULL,
  provider_code VARCHAR(100) NOT NULL,
  notify_channel VARCHAR(30) NOT NULL,
  provider_message_id VARCHAR(255) NOT NULL,
  notification_id BIGINT DEFAULT NULL,
  event_type VARCHAR(30) NOT NULL,
  dtmf_digit VARCHAR(5) DEFAULT NULL,
  event_time DATETIME(3) NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_provider_callback(provider_code,callback_id),
  KEY idx_provider_receipt_message(provider_code,provider_message_id,event_time),
  CONSTRAINT fk_provider_receipt_notification FOREIGN KEY(notification_id) REFERENCES t_alert_notification(id),
  CONSTRAINT chk_provider_receipt_channel CHECK(notify_channel IN ('FEISHU_CARD','FEISHU_ALL','FEISHU_DM','FEISHU_TASK','SMS','VOICE_CALL','EMAIL')),
  CONSTRAINT chk_provider_receipt_event CHECK(event_type IN ('ACCEPTED','DELIVERED','FAILED','BOUNCED','CONNECTED','NO_ANSWER','DTMF_ACK')),
  CONSTRAINT chk_provider_receipt_dtmf CHECK((event_type='DTMF_ACK' AND dtmf_digit IS NOT NULL) OR (event_type<>'DTMF_ACK' AND dtmf_digit IS NULL))
) ENGINE=InnoDB COMMENT='经HMAC验证的供应商回执；只保存载荷哈希，不保存通知正文';
