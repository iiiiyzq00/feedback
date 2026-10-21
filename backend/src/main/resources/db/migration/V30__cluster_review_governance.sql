-- D4-R01..R06: persisted priority/SLA, optimistic concurrency and review evidence.
ALTER TABLE t_cluster_link
  ADD COLUMN review_priority VARCHAR(2) DEFAULT NULL AFTER review_status,
  ADD COLUMN review_due_at DATETIME(3) DEFAULT NULL AFTER review_priority,
  ADD COLUMN review_version BIGINT NOT NULL DEFAULT 0 AFTER review_due_at,
  ADD COLUMN reviewed_by BIGINT DEFAULT NULL AFTER review_version,
  ADD COLUMN reviewed_at DATETIME(3) DEFAULT NULL AFTER reviewed_by,
  ADD COLUMN review_reason VARCHAR(500) DEFAULT NULL AFTER reviewed_at,
  ADD COLUMN review_request_id CHAR(36) DEFAULT NULL AFTER review_reason,
  ADD UNIQUE KEY uk_cluster_review_request (review_request_id),
  ADD KEY idx_cluster_review_sla (relation_role,review_status,review_priority,review_due_at,is_active);

UPDATE t_cluster_link l
JOIN t_feedback_content c ON c.source_id=l.feedback_source_id
SET l.review_priority=CASE
      WHEN c.severity_level IN ('S0','S1') THEN 'P1'
      WHEN c.severity_level='S2' THEN 'P2'
      ELSE 'P3'
    END,
    l.review_due_at=CASE
      WHEN c.severity_level IN ('S0','S1') THEN DATE_ADD(l.created_at,INTERVAL 4 HOUR)
      WHEN c.severity_level='S2' THEN DATE_ADD(l.created_at,INTERVAL 1 DAY)
      ELSE DATE_ADD(l.created_at,INTERVAL 3 DAY)
    END
WHERE l.relation_role='CANDIDATE';

ALTER TABLE t_cluster_link
  ADD CONSTRAINT chk_cluster_review_priority CHECK (
    (relation_role='MAIN' AND review_priority IS NULL AND review_due_at IS NULL) OR
    (relation_role='CANDIDATE' AND review_priority IN ('P1','P2','P3') AND review_due_at IS NOT NULL)
  ),
  ADD CONSTRAINT chk_cluster_review_version CHECK (review_version >= 0),
  ADD CONSTRAINT chk_cluster_review_completion CHECK (
    review_status IN ('PENDING','NOT_REQUIRED','SKIPPED') OR
    (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND review_reason IS NOT NULL)
  );
