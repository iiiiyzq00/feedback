-- Consolidate alert test, drill and acceptance workflow facts into t_ai_task.
ALTER TABLE t_ai_task
  DROP CONSTRAINT chk_ai_task_record_type,
  ADD COLUMN rule_id BIGINT DEFAULT NULL AFTER current_step,
  ADD COLUMN test_mode VARCHAR(20) DEFAULT NULL AFTER rule_id,
  ADD COLUMN domain_status VARCHAR(30) DEFAULT NULL AFTER test_mode,
  ADD COLUMN matched TINYINT(1) DEFAULT NULL AFTER domain_status,
  ADD COLUMN sample_start DATETIME(3) DEFAULT NULL AFTER matched,
  ADD COLUMN sample_end DATETIME(3) DEFAULT NULL AFTER sample_start,
  ADD COLUMN sample_size BIGINT DEFAULT NULL AFTER sample_end,
  ADD COLUMN external_notifications_sent INT DEFAULT NULL AFTER sample_size,
  ADD COLUMN reference_entity_id BIGINT DEFAULT NULL AFTER result_entity_id,
  ADD KEY idx_alert_governance_task (task_domain,record_type,rule_id,created_at,id),
  ADD KEY idx_alert_governance_status (task_domain,record_type,domain_status,rule_id,id),
  ADD CONSTRAINT chk_ai_task_record_type CHECK (record_type IN (
    'SKILL_DEF','PROMPT_VERSION','INVOCATION','ASSISTANT_QUERY','PROMPT_EVALUATION',
    'INDEX_OUTBOX','CLUSTER_EVALUATION','REPORT_IDEMPOTENCY','REPORT_GENERATION',
    'REPORT_SCHEDULE_RUN','RCA_ORCHESTRATION','RCA_STEP','ALERT_RULE_TEST',
    'ALERT_DRILL','ALERT_ACCEPTANCE'));

INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,rule_id,test_mode,
  task_key,task_status,domain_status,matched,input_snapshot,output_snapshot,snapshot_retention_until,
  sample_start,sample_end,sample_size,external_notifications_sent,user_id,created_at,completed_at)
SELECT 'ALERT_RULE_TEST','ALERT_GOVERNANCE','t_alert_rule_test_run',id,rule_id,test_mode,
  CONCAT('ALERT_RULE_TEST:',id),run_status,run_status,matched,input_snapshot,result_snapshot,
  DATE_ADD(created_at,INTERVAL 365 DAY),sample_start,sample_end,sample_size,
  external_notifications_sent,created_by,created_at,completed_at
FROM t_alert_rule_test_run
ON DUPLICATE KEY UPDATE task_status=VALUES(task_status),domain_status=VALUES(domain_status),
  output_snapshot=VALUES(output_snapshot),completed_at=VALUES(completed_at);

INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,rule_id,parent_task_id,
  task_key,task_status,domain_status,input_snapshot,snapshot_retention_until,user_id,created_at,completed_at)
SELECT 'ALERT_DRILL','ALERT_GOVERNANCE','t_alert_drill_instance',d.id,d.rule_id,t.id,
  CONCAT('ALERT_DRILL:',d.id),IF(d.drill_status='OPEN','RUNNING','COMPLETED'),d.drill_status,
  JSON_OBJECT('ruleCode',d.rule_code,'title',d.drill_title,'severity',d.severity,
    'notificationChannel',d.notification_channel,'productionImpact',d.production_impact),
  DATE_ADD(d.created_at,INTERVAL 365 DAY),d.created_by,d.created_at,d.closed_at
FROM t_alert_drill_instance d JOIN t_ai_task t
  ON t.legacy_source_table='t_alert_rule_test_run' AND t.legacy_source_id=d.test_run_id
ON DUPLICATE KEY UPDATE task_status=VALUES(task_status),domain_status=VALUES(domain_status),completed_at=VALUES(completed_at);

INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,rule_id,parent_task_id,
  reference_entity_id,task_key,task_status,domain_status,input_snapshot,output_snapshot,
  snapshot_retention_until,content_sha256,user_id,created_at,completed_at)
SELECT 'ALERT_ACCEPTANCE','ALERT_GOVERNANCE','t_alert_rule_acceptance_assessment',a.id,a.rule_id,r.id,
  a.drill_id,CONCAT('ALERT_ACCEPTANCE:',a.id),'COMPLETED',a.assessment_status,
  JSON_OBJECT('replayRunId',a.replay_run_id,'drillId',a.drill_id,'truePositive',a.true_positive,
    'falsePositive',a.false_positive,'falseNegative',a.false_negative,'trueNegative',a.true_negative),
  JSON_OBJECT('precisionRate',a.precision_rate,'recallRate',a.recall_rate,'routingOutcome',a.routing_outcome,
    'assessedBy',a.assessed_by,'approvedBy',a.approved_by,'assessedAt',a.assessed_at,'approvedAt',a.approved_at),
  DATE_ADD(a.assessed_at,INTERVAL 365 DAY),a.evidence_sha256,a.assessed_by,a.assessed_at,a.approved_at
FROM t_alert_rule_acceptance_assessment a JOIN t_ai_task r
  ON r.legacy_source_table='t_alert_rule_test_run' AND r.legacy_source_id=a.replay_run_id
ON DUPLICATE KEY UPDATE domain_status=VALUES(domain_status),output_snapshot=VALUES(output_snapshot),completed_at=VALUES(completed_at);

CREATE TRIGGER trg_alert_test_compact_insert AFTER INSERT ON t_alert_rule_test_run FOR EACH ROW
INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,rule_id,test_mode,task_key,
 task_status,domain_status,matched,input_snapshot,output_snapshot,snapshot_retention_until,sample_start,sample_end,
 sample_size,external_notifications_sent,user_id,created_at,completed_at)
VALUES('ALERT_RULE_TEST','ALERT_GOVERNANCE','t_alert_rule_test_run',NEW.id,NEW.rule_id,NEW.test_mode,
 CONCAT('ALERT_RULE_TEST:',NEW.id),NEW.run_status,NEW.run_status,NEW.matched,NEW.input_snapshot,NEW.result_snapshot,
 DATE_ADD(NEW.created_at,INTERVAL 365 DAY),NEW.sample_start,NEW.sample_end,NEW.sample_size,
 NEW.external_notifications_sent,NEW.created_by,NEW.created_at,NEW.completed_at);
CREATE TRIGGER trg_alert_test_compact_update AFTER UPDATE ON t_alert_rule_test_run FOR EACH ROW
UPDATE t_ai_task SET task_status=NEW.run_status,domain_status=NEW.run_status,matched=NEW.matched,
 input_snapshot=NEW.input_snapshot,output_snapshot=NEW.result_snapshot,sample_start=NEW.sample_start,
 sample_end=NEW.sample_end,sample_size=NEW.sample_size,external_notifications_sent=NEW.external_notifications_sent,
 completed_at=NEW.completed_at WHERE legacy_source_table='t_alert_rule_test_run' AND legacy_source_id=OLD.id;
CREATE TRIGGER trg_alert_test_compact_delete AFTER DELETE ON t_alert_rule_test_run FOR EACH ROW
DELETE FROM t_ai_task WHERE legacy_source_table='t_alert_rule_test_run' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_alert_drill_compact_insert AFTER INSERT ON t_alert_drill_instance FOR EACH ROW
INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,rule_id,parent_task_id,task_key,
 task_status,domain_status,input_snapshot,snapshot_retention_until,user_id,created_at,completed_at)
SELECT 'ALERT_DRILL','ALERT_GOVERNANCE','t_alert_drill_instance',NEW.id,NEW.rule_id,t.id,
 CONCAT('ALERT_DRILL:',NEW.id),IF(NEW.drill_status='OPEN','RUNNING','COMPLETED'),NEW.drill_status,
 JSON_OBJECT('ruleCode',NEW.rule_code,'title',NEW.drill_title,'severity',NEW.severity,
  'notificationChannel',NEW.notification_channel,'productionImpact',NEW.production_impact),
 DATE_ADD(NEW.created_at,INTERVAL 365 DAY),NEW.created_by,NEW.created_at,NEW.closed_at
FROM t_ai_task t WHERE t.legacy_source_table='t_alert_rule_test_run' AND t.legacy_source_id=NEW.test_run_id;
CREATE TRIGGER trg_alert_drill_compact_update AFTER UPDATE ON t_alert_drill_instance FOR EACH ROW
UPDATE t_ai_task SET task_status=IF(NEW.drill_status='OPEN','RUNNING','COMPLETED'),domain_status=NEW.drill_status,
 completed_at=NEW.closed_at WHERE legacy_source_table='t_alert_drill_instance' AND legacy_source_id=OLD.id;
CREATE TRIGGER trg_alert_drill_compact_delete AFTER DELETE ON t_alert_drill_instance FOR EACH ROW
DELETE FROM t_ai_task WHERE legacy_source_table='t_alert_drill_instance' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_alert_acceptance_compact_insert AFTER INSERT ON t_alert_rule_acceptance_assessment FOR EACH ROW
INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,rule_id,parent_task_id,
 reference_entity_id,task_key,task_status,domain_status,input_snapshot,output_snapshot,snapshot_retention_until,
 content_sha256,user_id,created_at,completed_at)
SELECT 'ALERT_ACCEPTANCE','ALERT_GOVERNANCE','t_alert_rule_acceptance_assessment',NEW.id,NEW.rule_id,r.id,
 NEW.drill_id,CONCAT('ALERT_ACCEPTANCE:',NEW.id),'COMPLETED',NEW.assessment_status,
 JSON_OBJECT('replayRunId',NEW.replay_run_id,'drillId',NEW.drill_id,'truePositive',NEW.true_positive,
  'falsePositive',NEW.false_positive,'falseNegative',NEW.false_negative,'trueNegative',NEW.true_negative),
 JSON_OBJECT('precisionRate',NEW.precision_rate,'recallRate',NEW.recall_rate,'routingOutcome',NEW.routing_outcome,
  'assessedBy',NEW.assessed_by,'approvedBy',NEW.approved_by,'assessedAt',NEW.assessed_at,'approvedAt',NEW.approved_at),
 DATE_ADD(NEW.assessed_at,INTERVAL 365 DAY),NEW.evidence_sha256,NEW.assessed_by,NEW.assessed_at,NEW.approved_at
FROM t_ai_task r WHERE r.legacy_source_table='t_alert_rule_test_run' AND r.legacy_source_id=NEW.replay_run_id;
CREATE TRIGGER trg_alert_acceptance_compact_update AFTER UPDATE ON t_alert_rule_acceptance_assessment FOR EACH ROW
UPDATE t_ai_task SET domain_status=NEW.assessment_status,
 output_snapshot=JSON_OBJECT('precisionRate',NEW.precision_rate,'recallRate',NEW.recall_rate,
  'routingOutcome',NEW.routing_outcome,'assessedBy',NEW.assessed_by,'approvedBy',NEW.approved_by,
  'assessedAt',NEW.assessed_at,'approvedAt',NEW.approved_at),completed_at=NEW.approved_at
WHERE legacy_source_table='t_alert_rule_acceptance_assessment' AND legacy_source_id=OLD.id;
CREATE TRIGGER trg_alert_acceptance_compact_delete AFTER DELETE ON t_alert_rule_acceptance_assessment FOR EACH ROW
DELETE FROM t_ai_task WHERE legacy_source_table='t_alert_rule_acceptance_assessment' AND legacy_source_id=OLD.id;
