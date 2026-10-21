-- Consolidate alert baseline/input-quality facts and immutable lifecycle evidence.
-- Legacy tables remain writable rollback shadows; triggers maintain the compact model.

ALTER TABLE t_alert_metric_hourly_bucket
  ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD UNIQUE KEY uk_alert_metric_bucket_id (id);

ALTER TABLE t_alert_occurrence
  MODIFY COLUMN instance_id BIGINT DEFAULT NULL,
  ADD COLUMN legacy_source_table VARCHAR(80) DEFAULT NULL AFTER dimension_key,
  ADD COLUMN legacy_source_id BIGINT DEFAULT NULL AFTER legacy_source_table,
  ADD COLUMN window_start DATETIME(3) DEFAULT NULL AFTER legacy_source_id,
  ADD COLUMN window_end DATETIME(3) DEFAULT NULL AFTER window_start,
  ADD COLUMN required_count BIGINT DEFAULT NULL AFTER window_end,
  ADD COLUMN present_count BIGINT DEFAULT NULL AFTER required_count,
  ADD COLUMN sample_count BIGINT DEFAULT NULL AFTER present_count,
  ADD COLUMN completeness DECIMAL(7,6) DEFAULT NULL AFTER sample_count,
  ADD COLUMN data_contract_version VARCHAR(50) DEFAULT NULL AFTER completeness,
  ADD UNIQUE KEY uk_occurrence_legacy_source (legacy_source_table,legacy_source_id),
  ADD KEY idx_occurrence_baseline_lookup
    (occurrence_type,metric_code,dimension_key,evaluation_time),
  ADD KEY idx_occurrence_quality_window
    (occurrence_type,window_end,completeness);

INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,
  legacy_source_table,legacy_source_id,window_start,window_end,sample_count,completeness,
  evaluation_snapshot,evaluation_time,created_at)
SELECT NULL,'HOURLY_BASELINE',b.metric_code,b.dimension_key,
  't_alert_metric_hourly_bucket',b.id,b.bucket_start,DATE_ADD(b.bucket_start,INTERVAL 1 HOUR),
  b.sample_count,b.completeness,
  JSON_OBJECT('calculatedAt',b.calculated_at),b.bucket_start,b.calculated_at
FROM t_alert_metric_hourly_bucket b
ON DUPLICATE KEY UPDATE metric_code=VALUES(metric_code),dimension_key=VALUES(dimension_key),
  window_start=VALUES(window_start),window_end=VALUES(window_end),sample_count=VALUES(sample_count),
  completeness=VALUES(completeness),evaluation_snapshot=VALUES(evaluation_snapshot),
  evaluation_time=VALUES(evaluation_time),created_at=VALUES(created_at);

INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,
  legacy_source_table,legacy_source_id,window_start,window_end,required_count,present_count,
  completeness,data_contract_version,evaluation_snapshot,evaluation_time,created_at)
SELECT NULL,'RULE_INPUT_QUALITY',q.rule_code,q.data_contract_version,
  't_alert_rule_input_quality_snapshot',q.id,q.window_start,q.window_end,q.required_count,
  q.present_count,q.completeness,q.data_contract_version,
  JSON_OBJECT('calculatedAt',q.calculated_at),q.window_end,q.calculated_at
FROM t_alert_rule_input_quality_snapshot q
ON DUPLICATE KEY UPDATE metric_code=VALUES(metric_code),dimension_key=VALUES(dimension_key),
  window_start=VALUES(window_start),window_end=VALUES(window_end),required_count=VALUES(required_count),
  present_count=VALUES(present_count),completeness=VALUES(completeness),
  data_contract_version=VALUES(data_contract_version),evaluation_snapshot=VALUES(evaluation_snapshot),
  evaluation_time=VALUES(evaluation_time),created_at=VALUES(created_at);

CREATE TRIGGER trg_alert_hourly_compact_insert AFTER INSERT ON t_alert_metric_hourly_bucket FOR EACH ROW
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,
  legacy_source_table,legacy_source_id,window_start,window_end,sample_count,completeness,
  evaluation_snapshot,evaluation_time,created_at)
VALUES(NULL,'HOURLY_BASELINE',NEW.metric_code,NEW.dimension_key,'t_alert_metric_hourly_bucket',NEW.id,
  NEW.bucket_start,DATE_ADD(NEW.bucket_start,INTERVAL 1 HOUR),NEW.sample_count,NEW.completeness,
  JSON_OBJECT('calculatedAt',NEW.calculated_at),NEW.bucket_start,NEW.calculated_at);

CREATE TRIGGER trg_alert_hourly_compact_update AFTER UPDATE ON t_alert_metric_hourly_bucket FOR EACH ROW
UPDATE t_alert_occurrence SET metric_code=NEW.metric_code,dimension_key=NEW.dimension_key,
  window_start=NEW.bucket_start,window_end=DATE_ADD(NEW.bucket_start,INTERVAL 1 HOUR),
  sample_count=NEW.sample_count,completeness=NEW.completeness,
  evaluation_snapshot=JSON_OBJECT('calculatedAt',NEW.calculated_at),evaluation_time=NEW.bucket_start,
  created_at=NEW.calculated_at
WHERE legacy_source_table='t_alert_metric_hourly_bucket' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_alert_hourly_compact_delete AFTER DELETE ON t_alert_metric_hourly_bucket FOR EACH ROW
DELETE FROM t_alert_occurrence
WHERE legacy_source_table='t_alert_metric_hourly_bucket' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_alert_quality_compact_insert AFTER INSERT ON t_alert_rule_input_quality_snapshot FOR EACH ROW
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,
  legacy_source_table,legacy_source_id,window_start,window_end,required_count,present_count,
  completeness,data_contract_version,evaluation_snapshot,evaluation_time,created_at)
VALUES(NULL,'RULE_INPUT_QUALITY',NEW.rule_code,NEW.data_contract_version,
  't_alert_rule_input_quality_snapshot',NEW.id,NEW.window_start,NEW.window_end,NEW.required_count,
  NEW.present_count,NEW.completeness,NEW.data_contract_version,
  JSON_OBJECT('calculatedAt',NEW.calculated_at),NEW.window_end,NEW.calculated_at);

CREATE TRIGGER trg_alert_quality_compact_update AFTER UPDATE ON t_alert_rule_input_quality_snapshot FOR EACH ROW
UPDATE t_alert_occurrence SET metric_code=NEW.rule_code,dimension_key=NEW.data_contract_version,
  window_start=NEW.window_start,window_end=NEW.window_end,required_count=NEW.required_count,
  present_count=NEW.present_count,completeness=NEW.completeness,
  data_contract_version=NEW.data_contract_version,
  evaluation_snapshot=JSON_OBJECT('calculatedAt',NEW.calculated_at),evaluation_time=NEW.window_end,
  created_at=NEW.calculated_at
WHERE legacy_source_table='t_alert_rule_input_quality_snapshot' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_alert_quality_compact_delete AFTER DELETE ON t_alert_rule_input_quality_snapshot FOR EACH ROW
DELETE FROM t_alert_occurrence
WHERE legacy_source_table='t_alert_rule_input_quality_snapshot' AND legacy_source_id=OLD.id;

ALTER TABLE t_system_audit_log
  ADD COLUMN legacy_source_table VARCHAR(80) DEFAULT NULL AFTER event_time,
  ADD COLUMN legacy_source_id BIGINT DEFAULT NULL AFTER legacy_source_table,
  ADD UNIQUE KEY uk_audit_legacy_source (legacy_source_table,legacy_source_id),
  ADD KEY idx_audit_target_timeline (operation,target_type,target_id,event_time,id);

INSERT INTO t_system_audit_log(user_id,operation,audit_class,outcome,target_type,target_id,
  operation_detail,detail_schema_version,event_time,legacy_source_table,legacy_source_id,created_at)
SELECT e.changed_by,
  IF(e.aggregate_type='ALERT','ALERT_LIFECYCLE_TRANSITION','INCIDENT_LIFECYCLE_TRANSITION'),
  'BUSINESS','SUCCESS',e.aggregate_type,CAST(e.aggregate_id AS CHAR),
  JSON_OBJECT('fromStatus',e.from_status,'toStatus',e.to_status,
    'lifecycleVersion',e.lifecycle_version,'reason',e.reason,'evidence',e.evidence),
  'alert-lifecycle-v1',e.changed_at,'t_alert_lifecycle_event',e.id,e.changed_at
FROM t_alert_lifecycle_event e
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),operation=VALUES(operation),
  target_type=VALUES(target_type),target_id=VALUES(target_id),operation_detail=VALUES(operation_detail),
  event_time=VALUES(event_time),created_at=VALUES(created_at);

CREATE TRIGGER trg_alert_lifecycle_compact_insert AFTER INSERT ON t_alert_lifecycle_event FOR EACH ROW
INSERT INTO t_system_audit_log(user_id,operation,audit_class,outcome,target_type,target_id,
  operation_detail,detail_schema_version,event_time,legacy_source_table,legacy_source_id,created_at)
VALUES(NEW.changed_by,
  IF(NEW.aggregate_type='ALERT','ALERT_LIFECYCLE_TRANSITION','INCIDENT_LIFECYCLE_TRANSITION'),
  'BUSINESS','SUCCESS',NEW.aggregate_type,CAST(NEW.aggregate_id AS CHAR),
  JSON_OBJECT('fromStatus',NEW.from_status,'toStatus',NEW.to_status,
    'lifecycleVersion',NEW.lifecycle_version,'reason',NEW.reason,'evidence',NEW.evidence),
  'alert-lifecycle-v1',NEW.changed_at,'t_alert_lifecycle_event',NEW.id,NEW.changed_at);
