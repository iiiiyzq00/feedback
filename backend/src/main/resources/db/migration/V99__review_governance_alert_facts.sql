-- Review-governance facts for solution 3.8.4. Runtime versions stay SHADOW
-- until replay, routing drill and independent contracts have been approved.
CREATE TABLE t_cluster_review_decision_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id CHAR(36) NOT NULL,
  feedback_source_id BIGINT NOT NULL,
  selected_cluster_id BIGINT DEFAULT NULL,
  top_candidate_cluster_id BIGINT DEFAULT NULL,
  decision_type VARCHAR(30) NOT NULL,
  is_overturn BOOLEAN NOT NULL,
  candidate_count INT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  decided_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_cluster_review_decision_request (request_id),
  KEY idx_cluster_review_decision_window (decided_at,is_overturn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='不可变问题簇人工审核决策事实';

CREATE TABLE t_masking_review_sla_policy (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_version VARCHAR(30) NOT NULL,
  queue_type VARCHAR(20) NOT NULL,
  urgency_class VARCHAR(20) NOT NULL DEFAULT 'ALL',
  max_minutes INT NOT NULL,
  effective_calendar VARCHAR(30) NOT NULL DEFAULT 'CONTINUOUS',
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_masking_review_sla_version_scope (policy_version,queue_type,urgency_class),
  CONSTRAINT chk_masking_review_sla_minutes CHECK(max_minutes BETWEEN 1 AND 10080)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='版本化脱敏审核SLA策略';

INSERT INTO t_masking_review_sla_policy(policy_version,queue_type,urgency_class,max_minutes,effective_calendar)
VALUES ('v1','SDK','URGENT',15,'CONTINUOUS'),('v1','SDK','ALL',120,'CONTINUOUS'),('v1','IMPORT','ALL',120,'CONTINUOUS');

CREATE TABLE t_alert_rule_input_quality_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_code VARCHAR(100) NOT NULL,
  window_start DATETIME(3) NOT NULL,
  window_end DATETIME(3) NOT NULL,
  required_count BIGINT NOT NULL,
  present_count BIGINT NOT NULL,
  completeness DECIMAL(7,6) NOT NULL,
  data_contract_version VARCHAR(50) NOT NULL,
  calculated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rule_input_quality_window (rule_code,window_start,window_end,data_contract_version),
  KEY idx_rule_input_quality_latest (window_end,completeness),
  CONSTRAINT chk_rule_input_quality_counts CHECK(required_count>=0 AND present_count>=0 AND present_count<=required_count),
  CONSTRAINT chk_rule_input_quality_ratio CHECK(completeness BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='规则依赖契约窗口完整度事实';

UPDATE t_alert_data_contract SET schema_version='cluster-review-queue-v1',source_system='t_cluster_link',freshness_seconds=900,completeness_threshold=.990000
WHERE contract_code IN ('cluster_review_queue','business_calendar') AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='cluster-review-decision-v1',source_system='t_cluster_review_decision_event',freshness_seconds=86400,completeness_threshold=.990000
WHERE contract_code='cluster_review_decision' AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='masking-review-sla-v1',source_system='t_masking_review_sla_policy',freshness_seconds=900,completeness_threshold=.990000
WHERE contract_code IN ('masking_review_queue','sla_config') AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='rule-input-quality-v1',source_system='t_alert_rule_input_quality_snapshot',freshness_seconds=300,completeness_threshold=.990000
WHERE contract_code='rule_input_completeness' AND contract_status='DRAFT';

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'CLUSTER_REVIEW_SLA_BREACH','问题簇审核SLA超时','DATA_QUALITY','产品运营',330,'shadow-v1','SHADOW','P2',JSON_OBJECT('conditions',JSON_ARRAY(JSON_OBJECT('metric','CLUSTER_REVIEW_SLA_BREACH_COUNT','operator','>=','threshold',1,'window','15m'))),JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='CLUSTER_REVIEW_SLA_BREACH' AND version_status='SHADOW');
INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'MASKING_REVIEW_SLA_BREACH','脱敏审核SLA超时','MASKING','隐私运营',320,'shadow-v1','SHADOW','P2',JSON_OBJECT('conditions',JSON_ARRAY(JSON_OBJECT('metric','MASKING_REVIEW_SLA_BREACH_COUNT','operator','>=','threshold',1,'window','15m'))),JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='MASKING_REVIEW_SLA_BREACH' AND version_status='SHADOW');
INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'CLUSTER_MANUAL_OVERTURN_HIGH','问题簇人工推翻率过高','DATA_QUALITY','AI平台+产品运营',340,'shadow-v1','SHADOW','P2',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','CLUSTER_REVIEW_DECISION_COUNT','operator','>=','threshold',30,'window','24h'),JSON_OBJECT('metric','CLUSTER_MANUAL_OVERTURN_RATE','operator','>','threshold',20,'window','24h'))),JSON_OBJECT('threshold',10,'consecutiveWindows',2),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),1440,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='CLUSTER_MANUAL_OVERTURN_HIGH' AND version_status='SHADOW');
INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'RULE_INPUT_DATA_INCOMPLETE','规则输入数据不完整','DATA_QUALITY','数据平台',350,'shadow-v1','SHADOW','P1',JSON_OBJECT('conditions',JSON_ARRAY(JSON_OBJECT('metric','RULE_INPUT_COMPLETENESS_PERCENT','operator','<','threshold',95,'window','5m'))),JSON_OBJECT('threshold',99,'consecutiveWindows',2),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),5,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='RULE_INPUT_DATA_INCOMPLETE' AND version_status='SHADOW');
