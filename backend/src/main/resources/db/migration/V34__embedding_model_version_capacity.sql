-- Preserve the complete governed model identifier; ONNX snapshot identifiers exceed the legacy 50 chars.
ALTER TABLE t_feedback_ai MODIFY COLUMN embedding_model_version VARCHAR(200) DEFAULT NULL;

UPDATE t_ai_task SET task_status='RETRY_PENDING',retry_count=0,next_retry_at=NOW(3),lease_holder=NULL,lease_expires_at=NULL,
  error_detail=CONCAT('SCHEMA_CAPACITY_REPLAY: ',COALESCE(error_detail,''))
WHERE record_type='INDEX_OUTBOX' AND task_status IN ('FAILED','RETRY_PENDING');
