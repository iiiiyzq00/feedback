-- D4-S01..S04 + I01..I04: statistics reconciliation and durable derived-index outbox.
ALTER TABLE t_cluster
  ADD COLUMN statistics_version BIGINT NOT NULL DEFAULT 0 AFTER lifecycle_version,
  ADD COLUMN statistics_reconciled_at DATETIME(3) DEFAULT NULL AFTER statistics_version,
  ADD CONSTRAINT chk_cluster_statistics_version CHECK (statistics_version >= 0),
  ADD KEY idx_cluster_statistics_reconcile (statistics_reconciled_at,id);

ALTER TABLE t_ai_task DROP CHECK chk_ai_task_record_type;
ALTER TABLE t_ai_task ADD CONSTRAINT chk_ai_task_record_type CHECK (
  record_type IN ('SKILL_DEF','PROMPT_VERSION','INVOCATION','ASSISTANT_QUERY','PROMPT_EVALUATION','INDEX_OUTBOX')
);

ALTER TABLE t_ai_task
  ADD KEY idx_index_outbox_claim (record_type,task_status,next_retry_at,lease_expires_at,id);

-- Existing durable PENDING flags are converted to idempotent outbox jobs.
INSERT IGNORE INTO t_ai_task(record_type,source_id,task_key,task_status,input_snapshot,next_retry_at)
SELECT 'INDEX_OUTBOX',a.source_id,CONCAT('INDEX:BOTH:',a.source_id,':',UNIX_TIMESTAMP(a.updated_at)*1000),
       'PENDING',JSON_OBJECT('operation','UPSERT','targets',JSON_ARRAY('SEARCH','VECTOR'),'sourceId',a.source_id),NOW(3)
FROM t_feedback_ai a
JOIN t_feedback_content c ON c.source_id=a.source_id
WHERE c.masking_status='PASS' AND c.is_deleted=0
  AND (a.search_index_status IN ('PENDING','FAILED') OR a.vector_index_status IN ('PENDING','FAILED'));
