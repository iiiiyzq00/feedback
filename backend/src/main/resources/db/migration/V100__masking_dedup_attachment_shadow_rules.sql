-- Solution 3.8.4: masking release quality, confirmed incorrect dedup and
-- attachment final-state quality. All executable versions remain isolated.
CREATE TABLE t_dedup_review_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  feedback_source_id BIGINT NOT NULL,
  related_source_id BIGINT DEFAULT NULL,
  error_type VARCHAR(30) NOT NULL,
  urgency_class VARCHAR(20) NOT NULL,
  review_status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
  confirmation_note VARCHAR(500) NOT NULL,
  confirmed_by BIGINT NOT NULL,
  confirmed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  repair_note VARCHAR(500) DEFAULT NULL,
  repaired_by BIGINT DEFAULT NULL,
  repaired_at DATETIME(3) DEFAULT NULL,
  KEY idx_dedup_review_open (review_status,urgency_class,confirmed_at),
  CONSTRAINT chk_dedup_review_type CHECK(error_type IN ('INCORRECT_OVERWRITE','INCORRECT_SKIP','INCORRECT_MERGE')),
  CONSTRAINT chk_dedup_review_status CHECK(review_status IN ('CONFIRMED','REPAIRED','CLOSED')),
  CONSTRAINT chk_dedup_review_urgency CHECK(urgency_class IN ('URGENT','NORMAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='人工确认的错误去重与修复闭环事实';

UPDATE t_alert_data_contract SET schema_version='masking-evaluation-run-v1',source_system='t_masking_evaluation_run',freshness_seconds=86400,completeness_threshold=.990000
WHERE contract_code='masking_evaluation' AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='dedup-review-event-v1',source_system='t_dedup_review_event',freshness_seconds=60,completeness_threshold=.990000
WHERE contract_code IN ('dedup_review_event','urgency') AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='attachment-final-status-v1',source_system='t_feedback_attachment+t_import_attachment_task',freshness_seconds=900,completeness_threshold=.990000
WHERE contract_code='attachment_final_status' AND contract_status='DRAFT';

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'MASKING_RULE_QUALITY_DEGRADED','脱敏规则质量下降','MASKING','隐私+数据平台',360,'shadow-v1','SHADOW','P1',
 JSON_OBJECT('combinator','ANY','conditions',JSON_ARRAY(JSON_OBJECT('metric','MASKING_LATEST_END_TO_END_F1_PERCENT','operator','<','threshold',90,'window','24h'),JSON_OBJECT('metric','MASKING_LATEST_HIGH_RISK_RECALL_PERCENT','operator','<','threshold',98,'window','24h'))),
 JSON_OBJECT('threshold',100,'factClearedBy','NEW_PASSED_VERSION'),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),60,0
WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='MASKING_RULE_QUALITY_DEGRADED' AND version_status='SHADOW');

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'INCORRECT_DEDUP_DETECTED','错误去重确认','DATA_QUALITY','数据平台',370,'shadow-v1','SHADOW','P1',
 JSON_OBJECT('conditions',JSON_ARRAY(JSON_OBJECT('metric','OPEN_INCORRECT_DEDUP_COUNT','operator','>=','threshold',1,'window','24h'))),
 JSON_OBJECT('threshold',0,'manualRepairRequired',true),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0
WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='INCORRECT_DEDUP_DETECTED' AND version_status='SHADOW');

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'ATTACHMENT_FAILURE_RATE_HIGH','附件失败率过高','DATA_INGEST','数据平台',380,'shadow-v1','SHADOW','P2',
 JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','ATTACHMENT_FINAL_COUNT','operator','>=','threshold',20,'window','1h'),JSON_OBJECT('metric','ATTACHMENT_FINAL_FAILURE_RATE','operator','>','threshold',10,'window','1h'))),
 JSON_OBJECT('threshold',5,'consecutiveWindows',2),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),60,0
WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='ATTACHMENT_FAILURE_RATE_HIGH' AND version_status='SHADOW');
