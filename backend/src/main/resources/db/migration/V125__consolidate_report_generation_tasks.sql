-- Consolidate report idempotency, generation tasks and schedule runs into t_ai_task.
-- Legacy tables remain writable shadows and keep their external identifiers.

ALTER TABLE t_ai_task
  DROP CONSTRAINT chk_ai_task_record_type,
  DROP CONSTRAINT chk_ai_task_status,
  ADD COLUMN legacy_source_table VARCHAR(50) DEFAULT NULL AFTER task_domain,
  ADD COLUMN legacy_source_id BIGINT DEFAULT NULL AFTER legacy_source_table,
  ADD COLUMN public_id BINARY(16) DEFAULT NULL AFTER legacy_source_id,
  ADD COLUMN generation_source VARCHAR(20) DEFAULT NULL AFTER public_id,
  ADD COLUMN cancel_requested TINYINT(1) NOT NULL DEFAULT 0 AFTER generation_source,
  ADD COLUMN max_retries INT NOT NULL DEFAULT 3 AFTER retry_count,
  ADD COLUMN error_code VARCHAR(100) DEFAULT NULL AFTER snapshot_deleted_at,
  ADD COLUMN started_at DATETIME(3) DEFAULT NULL AFTER error_detail,
  ADD COLUMN result_entity_type VARCHAR(30) DEFAULT NULL AFTER started_at,
  ADD COLUMN result_entity_id BIGINT DEFAULT NULL AFTER result_entity_type,
  ADD UNIQUE KEY uk_unified_task_legacy (legacy_source_table,legacy_source_id),
  ADD KEY idx_unified_task_result (result_entity_type,result_entity_id),
  ADD KEY idx_unified_task_public (public_id);

ALTER TABLE t_ai_task
  ADD CONSTRAINT chk_ai_task_record_type CHECK (record_type IN (
    'SKILL_DEF','PROMPT_VERSION','INVOCATION','ASSISTANT_QUERY','PROMPT_EVALUATION',
    'INDEX_OUTBOX','CLUSTER_EVALUATION','REPORT_IDEMPOTENCY','REPORT_GENERATION',
    'REPORT_SCHEDULE_RUN','RCA_ORCHESTRATION','RCA_STEP')),
  ADD CONSTRAINT chk_ai_task_status CHECK (task_status IS NULL OR task_status IN (
    'PENDING','PROCESSING','RUNNING','COMPLETED','FAILED','RETRY_PENDING','CANCELLED',
    'SUCCEEDED','SKIPPED_LOCK'));

INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,task_key,
  task_status,input_snapshot,output_snapshot,snapshot_retention_until,created_at,completed_at,result_entity_type,result_entity_id)
SELECT 'REPORT_IDEMPOTENCY','REPORT','t_report_generation_key',id,CONCAT('REPORT_KEY:',id),
  IF(report_id IS NULL,'PENDING','COMPLETED'),
  JSON_OBJECT('idempotencyKey',idempotency_key,'generationSource',generation_source,
    'baseKey',base_key,'regenerationNo',regeneration_no),
  JSON_OBJECT('reportId',report_id),DATE_ADD(created_at,INTERVAL 365 DAY),created_at,completed_at,'AI_REPORT',report_id
FROM t_report_generation_key
ON DUPLICATE KEY UPDATE task_status=VALUES(task_status),input_snapshot=VALUES(input_snapshot),
  output_snapshot=VALUES(output_snapshot),completed_at=VALUES(completed_at),result_entity_id=VALUES(result_entity_id);

INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,public_id,
  generation_source,task_key,user_id,task_status,cancel_requested,retry_count,max_retries,
  lease_holder,lease_expires_at,next_retry_at,input_snapshot,snapshot_retention_until,error_detail,error_code,created_at,
  started_at,completed_at,result_entity_type,result_entity_id)
SELECT 'REPORT_GENERATION','REPORT','t_report_generation_task',id,public_id,generation_source,
  CONCAT('REPORT_TASK:',id),actor_id,task_status,cancel_requested,retry_count,max_retries,
  lease_holder,lease_expires_at,next_retry_at,request_snapshot,DATE_ADD(created_at,INTERVAL 365 DAY),error_detail,error_code,created_at,
  started_at,completed_at,'AI_REPORT',report_id
FROM t_report_generation_task
ON DUPLICATE KEY UPDATE task_status=VALUES(task_status),cancel_requested=VALUES(cancel_requested),
  retry_count=VALUES(retry_count),max_retries=VALUES(max_retries),lease_holder=VALUES(lease_holder),
  lease_expires_at=VALUES(lease_expires_at),next_retry_at=VALUES(next_retry_at),
  input_snapshot=VALUES(input_snapshot),error_detail=VALUES(error_detail),error_code=VALUES(error_code),
  started_at=VALUES(started_at),completed_at=VALUES(completed_at),result_entity_id=VALUES(result_entity_id);

INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,task_key,
  task_status,input_snapshot,output_snapshot,snapshot_retention_until,error_code,error_detail,created_at,started_at,
  completed_at,result_entity_type,result_entity_id)
SELECT 'REPORT_SCHEDULE_RUN','REPORT','t_report_schedule_run',id,CONCAT('REPORT_SCHEDULE:',id),
  run_status,JSON_OBJECT('scheduleKey',schedule_key,'reportType',report_type,
    'periodStart',period_start,'periodEnd',period_end),
  JSON_OBJECT('lockAcquired',lock_acquired,'reportId',report_id),DATE_ADD(created_at,INTERVAL 365 DAY),error_code,error_message,
  created_at,started_at,finished_at,'AI_REPORT',report_id
FROM t_report_schedule_run
ON DUPLICATE KEY UPDATE task_status=VALUES(task_status),input_snapshot=VALUES(input_snapshot),
  output_snapshot=VALUES(output_snapshot),error_code=VALUES(error_code),error_detail=VALUES(error_detail),
  completed_at=VALUES(completed_at),result_entity_id=VALUES(result_entity_id);

CREATE TRIGGER trg_report_task_compact_insert AFTER INSERT ON t_report_generation_task FOR EACH ROW
INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,public_id,generation_source,
  task_key,user_id,task_status,cancel_requested,retry_count,max_retries,lease_holder,lease_expires_at,
  next_retry_at,input_snapshot,snapshot_retention_until,error_detail,error_code,created_at,started_at,completed_at,result_entity_type,result_entity_id)
VALUES('REPORT_GENERATION','REPORT','t_report_generation_task',NEW.id,NEW.public_id,NEW.generation_source,
  CONCAT('REPORT_TASK:',NEW.id),NEW.actor_id,NEW.task_status,NEW.cancel_requested,NEW.retry_count,
  NEW.max_retries,NEW.lease_holder,NEW.lease_expires_at,NEW.next_retry_at,NEW.request_snapshot,DATE_ADD(NEW.created_at,INTERVAL 365 DAY),
  NEW.error_detail,NEW.error_code,NEW.created_at,NEW.started_at,NEW.completed_at,'AI_REPORT',NEW.report_id);

CREATE TRIGGER trg_report_task_compact_update AFTER UPDATE ON t_report_generation_task FOR EACH ROW
UPDATE t_ai_task SET task_status=NEW.task_status,cancel_requested=NEW.cancel_requested,
  retry_count=NEW.retry_count,max_retries=NEW.max_retries,lease_holder=NEW.lease_holder,
  lease_expires_at=NEW.lease_expires_at,next_retry_at=NEW.next_retry_at,input_snapshot=NEW.request_snapshot,
  error_detail=NEW.error_detail,error_code=NEW.error_code,started_at=NEW.started_at,
  completed_at=NEW.completed_at,result_entity_id=NEW.report_id
WHERE legacy_source_table='t_report_generation_task' AND legacy_source_id=NEW.id;

CREATE TRIGGER trg_report_key_compact_insert AFTER INSERT ON t_report_generation_key FOR EACH ROW
INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,task_key,task_status,
  input_snapshot,output_snapshot,snapshot_retention_until,created_at,completed_at,result_entity_type,result_entity_id)
VALUES('REPORT_IDEMPOTENCY','REPORT','t_report_generation_key',NEW.id,CONCAT('REPORT_KEY:',NEW.id),
  IF(NEW.report_id IS NULL,'PENDING','COMPLETED'),JSON_OBJECT('idempotencyKey',NEW.idempotency_key,
  'generationSource',NEW.generation_source,'baseKey',NEW.base_key,'regenerationNo',NEW.regeneration_no),
  JSON_OBJECT('reportId',NEW.report_id),DATE_ADD(NEW.created_at,INTERVAL 365 DAY),NEW.created_at,NEW.completed_at,'AI_REPORT',NEW.report_id);

CREATE TRIGGER trg_report_key_compact_update AFTER UPDATE ON t_report_generation_key FOR EACH ROW
UPDATE t_ai_task SET task_status=IF(NEW.report_id IS NULL,'PENDING','COMPLETED'),
  output_snapshot=JSON_OBJECT('reportId',NEW.report_id),completed_at=NEW.completed_at,
  result_entity_id=NEW.report_id
WHERE legacy_source_table='t_report_generation_key' AND legacy_source_id=NEW.id;

CREATE TRIGGER trg_report_schedule_compact_insert AFTER INSERT ON t_report_schedule_run FOR EACH ROW
INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,task_key,task_status,
  input_snapshot,output_snapshot,snapshot_retention_until,error_code,error_detail,created_at,started_at,completed_at,
  result_entity_type,result_entity_id)
VALUES('REPORT_SCHEDULE_RUN','REPORT','t_report_schedule_run',NEW.id,CONCAT('REPORT_SCHEDULE:',NEW.id),
  NEW.run_status,JSON_OBJECT('scheduleKey',NEW.schedule_key,'reportType',NEW.report_type,
  'periodStart',NEW.period_start,'periodEnd',NEW.period_end),JSON_OBJECT('lockAcquired',NEW.lock_acquired,
  'reportId',NEW.report_id),DATE_ADD(NEW.created_at,INTERVAL 365 DAY),NEW.error_code,NEW.error_message,NEW.created_at,NEW.started_at,
  NEW.finished_at,'AI_REPORT',NEW.report_id);

CREATE TRIGGER trg_report_schedule_compact_update AFTER UPDATE ON t_report_schedule_run FOR EACH ROW
UPDATE t_ai_task SET task_status=NEW.run_status,
  output_snapshot=JSON_OBJECT('lockAcquired',NEW.lock_acquired,'reportId',NEW.report_id),
  error_code=NEW.error_code,error_detail=NEW.error_message,completed_at=NEW.finished_at,
  result_entity_id=NEW.report_id
WHERE legacy_source_table='t_report_schedule_run' AND legacy_source_id=NEW.id;
