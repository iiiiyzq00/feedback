-- Consolidate durable RCA orchestration tasks and steps into t_ai_task.

ALTER TABLE t_ai_task
  ADD COLUMN trigger_type VARCHAR(20) DEFAULT NULL AFTER generation_source,
  ADD COLUMN current_step VARCHAR(30) DEFAULT NULL AFTER trigger_type,
  ADD KEY idx_unified_task_step (task_domain,record_type,current_step,task_status);

INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,generation_source,
  trigger_type,current_step,task_key,correlation_key,user_id,task_status,cancel_requested,retry_count,max_retries,
  lease_holder,lease_expires_at,next_retry_at,input_snapshot,snapshot_retention_until,error_code,
  error_detail,created_at,started_at,completed_at,result_entity_type,result_entity_id)
SELECT 'RCA_ORCHESTRATION','RCA','t_rca_orchestration_task',id,trigger_type,trigger_type,
  current_step,CONCAT('RCA_TASK:',id),task_key,created_by,task_status,cancel_requested,retry_count,max_retries,
  lease_owner,lease_until,next_retry_at,request_snapshot,DATE_ADD(created_at,INTERVAL 365 DAY),
  error_code,error_detail,created_at,started_at,completed_at,'ROOT_CAUSE_ANALYSIS',result_rca_id
FROM t_rca_orchestration_task
ON DUPLICATE KEY UPDATE task_status=VALUES(task_status),current_step=VALUES(current_step),
  cancel_requested=VALUES(cancel_requested),retry_count=VALUES(retry_count),max_retries=VALUES(max_retries),
  lease_holder=VALUES(lease_holder),lease_expires_at=VALUES(lease_expires_at),
  next_retry_at=VALUES(next_retry_at),input_snapshot=VALUES(input_snapshot),error_code=VALUES(error_code),
  error_detail=VALUES(error_detail),started_at=VALUES(started_at),completed_at=VALUES(completed_at),
  result_entity_id=VALUES(result_entity_id);

INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,parent_task_id,
  task_sequence,current_step,task_key,task_status,retry_count,input_snapshot,output_snapshot,
  snapshot_retention_until,content_sha256,error_code,error_detail,created_at,started_at,completed_at)
SELECT 'RCA_STEP','RCA','t_rca_orchestration_step',s.id,t.id,
  FIELD(s.step_code,'FACTS','CORRELATION','FIVE_WHYS','IMPACT','PERSIST'),s.step_code,
  CONCAT('RCA_STEP:',s.id),s.step_status,s.attempt_count,s.input_snapshot,s.output_snapshot,
  IF(s.input_snapshot IS NULL AND s.output_snapshot IS NULL,NULL,DATE_ADD(s.created_at,INTERVAL 365 DAY)),
  LOWER(HEX(s.output_sha256)),s.error_code,s.error_detail,s.created_at,s.started_at,s.completed_at
FROM t_rca_orchestration_step s
JOIN t_ai_task t ON t.legacy_source_table='t_rca_orchestration_task' AND t.legacy_source_id=s.task_id
ON DUPLICATE KEY UPDATE parent_task_id=VALUES(parent_task_id),task_status=VALUES(task_status),
  retry_count=VALUES(retry_count),input_snapshot=VALUES(input_snapshot),output_snapshot=VALUES(output_snapshot),
  snapshot_retention_until=VALUES(snapshot_retention_until),content_sha256=VALUES(content_sha256),
  error_code=VALUES(error_code),error_detail=VALUES(error_detail),started_at=VALUES(started_at),
  completed_at=VALUES(completed_at);

CREATE TRIGGER trg_rca_task_compact_insert AFTER INSERT ON t_rca_orchestration_task FOR EACH ROW
INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,generation_source,
  trigger_type,current_step,task_key,correlation_key,user_id,task_status,cancel_requested,retry_count,max_retries,
  lease_holder,lease_expires_at,next_retry_at,input_snapshot,snapshot_retention_until,error_code,
  error_detail,created_at,started_at,completed_at,result_entity_type,result_entity_id)
VALUES('RCA_ORCHESTRATION','RCA','t_rca_orchestration_task',NEW.id,NEW.trigger_type,NEW.trigger_type,
  NEW.current_step,CONCAT('RCA_TASK:',NEW.id),NEW.task_key,NEW.created_by,NEW.task_status,NEW.cancel_requested,
  NEW.retry_count,NEW.max_retries,NEW.lease_owner,NEW.lease_until,NEW.next_retry_at,NEW.request_snapshot,
  DATE_ADD(NEW.created_at,INTERVAL 365 DAY),NEW.error_code,NEW.error_detail,NEW.created_at,NEW.started_at,
  NEW.completed_at,'ROOT_CAUSE_ANALYSIS',NEW.result_rca_id);

CREATE TRIGGER trg_rca_task_compact_update AFTER UPDATE ON t_rca_orchestration_task FOR EACH ROW
UPDATE t_ai_task SET task_status=NEW.task_status,current_step=NEW.current_step,
  cancel_requested=NEW.cancel_requested,retry_count=NEW.retry_count,max_retries=NEW.max_retries,
  lease_holder=NEW.lease_owner,lease_expires_at=NEW.lease_until,next_retry_at=NEW.next_retry_at,
  input_snapshot=NEW.request_snapshot,error_code=NEW.error_code,error_detail=NEW.error_detail,
  started_at=NEW.started_at,completed_at=NEW.completed_at,result_entity_id=NEW.result_rca_id
WHERE legacy_source_table='t_rca_orchestration_task' AND legacy_source_id=NEW.id;

CREATE TRIGGER trg_rca_task_compact_delete AFTER DELETE ON t_rca_orchestration_task FOR EACH ROW
DELETE FROM t_ai_task
WHERE legacy_source_table='t_rca_orchestration_task' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_rca_step_compact_insert AFTER INSERT ON t_rca_orchestration_step FOR EACH ROW
INSERT INTO t_ai_task(record_type,task_domain,legacy_source_table,legacy_source_id,parent_task_id,
  task_sequence,current_step,task_key,task_status,retry_count,input_snapshot,output_snapshot,
  snapshot_retention_until,content_sha256,error_code,error_detail,created_at,started_at,completed_at)
SELECT 'RCA_STEP','RCA','t_rca_orchestration_step',NEW.id,t.id,
  FIELD(NEW.step_code,'FACTS','CORRELATION','FIVE_WHYS','IMPACT','PERSIST'),NEW.step_code,
  CONCAT('RCA_STEP:',NEW.id),NEW.step_status,NEW.attempt_count,NEW.input_snapshot,NEW.output_snapshot,
  IF(NEW.input_snapshot IS NULL AND NEW.output_snapshot IS NULL,NULL,DATE_ADD(NEW.created_at,INTERVAL 365 DAY)),
  LOWER(HEX(NEW.output_sha256)),NEW.error_code,NEW.error_detail,NEW.created_at,NEW.started_at,NEW.completed_at
FROM t_ai_task t
WHERE t.legacy_source_table='t_rca_orchestration_task' AND t.legacy_source_id=NEW.task_id;

CREATE TRIGGER trg_rca_step_compact_update AFTER UPDATE ON t_rca_orchestration_step FOR EACH ROW
UPDATE t_ai_task SET task_status=NEW.step_status,retry_count=NEW.attempt_count,
  input_snapshot=NEW.input_snapshot,output_snapshot=NEW.output_snapshot,
  snapshot_retention_until=IF(NEW.input_snapshot IS NULL AND NEW.output_snapshot IS NULL,NULL,
    COALESCE(snapshot_retention_until,DATE_ADD(NEW.created_at,INTERVAL 365 DAY))),
  content_sha256=LOWER(HEX(NEW.output_sha256)),error_code=NEW.error_code,
  error_detail=NEW.error_detail,started_at=NEW.started_at,completed_at=NEW.completed_at
WHERE legacy_source_table='t_rca_orchestration_step' AND legacy_source_id=NEW.id;

CREATE TRIGGER trg_rca_step_compact_delete AFTER DELETE ON t_rca_orchestration_step FOR EACH ROW
DELETE FROM t_ai_task
WHERE legacy_source_table='t_rca_orchestration_step' AND legacy_source_id=OLD.id;
