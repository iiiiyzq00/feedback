CREATE TABLE t_idempotency_conflict_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_channel VARCHAR(20) NOT NULL,
  source_record_id_hash CHAR(64) NOT NULL,
  import_batch_id BIGINT NOT NULL,
  import_row_id BIGINT NOT NULL,
  existing_source_id BIGINT NOT NULL,
  existing_fingerprint_hash CHAR(64) DEFAULT NULL,
  incoming_fingerprint_hash CHAR(64) NOT NULL,
  conflict_reason VARCHAR(100) NOT NULL,
  conflict_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  resolution_action VARCHAR(30) DEFAULT NULL,
  resolution_note VARCHAR(500) DEFAULT NULL,
  resolved_by BIGINT DEFAULT NULL,
  resolved_at DATETIME(3) DEFAULT NULL,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_idempotency_conflict_row(import_row_id),
  KEY idx_idempotency_conflict_status_time(conflict_status,occurred_at),
  CONSTRAINT chk_idempotency_conflict_status CHECK(conflict_status IN ('OPEN','RESOLVED','IGNORED')),
  CONSTRAINT chk_idempotency_conflict_resolution CHECK(
    (conflict_status='OPEN' AND resolved_at IS NULL AND resolved_by IS NULL)
    OR (conflict_status<>'OPEN' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='不可变幂等冲突事实及独立处置状态';

UPDATE t_alert_data_contract SET schema_version='idempotency-conflict-v1',source_system='t_idempotency_conflict_event',freshness_seconds=60,completeness_threshold=.999000
WHERE contract_code='idempotency_conflict_event' AND contract_status='DRAFT';

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'UNHANDLED_IDEMPOTENCY_CONFLICT','未处理幂等冲突','DATA_QUALITY','数据平台',180,'shadow-v1','SHADOW','P2',
 JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','UNHANDLED_IDEMPOTENCY_CONFLICT_COUNT','operator','>=','threshold',1,'window','24h'))),
 JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0
WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='UNHANDLED_IDEMPOTENCY_CONFLICT' AND version_status='SHADOW');
