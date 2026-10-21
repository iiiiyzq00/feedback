ALTER TABLE t_ai_task
  ADD COLUMN task_key VARCHAR(200) DEFAULT NULL AFTER source_id,
  ADD COLUMN next_retry_at DATETIME(3) DEFAULT NULL AFTER lease_expires_at,
  ADD COLUMN completed_at DATETIME(3) DEFAULT NULL AFTER error_detail,
  ADD UNIQUE KEY uk_ai_task_key (task_key),
  ADD KEY idx_ai_task_retry (record_type, task_status, next_retry_at, lease_expires_at, created_at);

ALTER TABLE t_cluster_link
  ADD COLUMN link_type VARCHAR(20) NOT NULL DEFAULT 'NEW_CLUSTER' AFTER feedback_source_id,
  ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED' AFTER decision_version,
  ADD COLUMN decision_reason VARCHAR(500) DEFAULT NULL AFTER review_status,
  ADD CONSTRAINT chk_cluster_link_type CHECK (link_type IN ('NEW_CLUSTER','AUTO_HIGH','AUTO_MEDIUM','MANUAL_REVIEW')),
  ADD CONSTRAINT chk_cluster_review_status CHECK (review_status IN ('NOT_REQUIRED','PENDING','CONFIRMED','REJECTED'));

ALTER TABLE t_feedback_ai
  DROP CHECK chk_ai_review_fields,
  DROP CHECK chk_ai_review_status,
  ADD CONSTRAINT chk_ai_review_status CHECK (review_status IN ('NOT_REQUIRED','PENDING','REVIEWED','OVERRULED')),
  ADD CONSTRAINT chk_ai_review_fields CHECK (
    review_status IN ('PENDING','NOT_REQUIRED')
    OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
  );
