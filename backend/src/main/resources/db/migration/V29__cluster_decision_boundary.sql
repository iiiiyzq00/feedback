-- D4-C01..C12: separate the single current main association from review candidates.
ALTER TABLE t_cluster_link
  DROP INDEX uk_active_feedback,
  DROP COLUMN active_feedback_source_id,
  DROP CHECK chk_cluster_link_type,
  DROP CHECK chk_cluster_review_status,
  ADD COLUMN relation_role VARCHAR(20) NOT NULL DEFAULT 'MAIN' AFTER feedback_source_id,
  ADD COLUMN candidate_rank INT DEFAULT NULL AFTER match_similarity,
  ADD COLUMN decision_config_version VARCHAR(30) DEFAULT NULL AFTER decision_version,
  ADD COLUMN feature_snapshot JSON DEFAULT NULL AFTER decision_config_version;

ALTER TABLE t_cluster_link
  MODIFY COLUMN link_type VARCHAR(30) NOT NULL DEFAULT 'NEW_CLUSTER',
  MODIFY COLUMN decision_version VARCHAR(30) DEFAULT NULL,
  ADD COLUMN active_feedback_source_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN is_active AND relation_role='MAIN' THEN feedback_source_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_active_feedback (active_feedback_source_id),
  ADD KEY idx_review_candidate (feedback_source_id, relation_role, review_status, is_active, candidate_rank),
  ADD CONSTRAINT chk_cluster_relation_role CHECK (relation_role IN ('MAIN','CANDIDATE')),
  ADD CONSTRAINT chk_cluster_candidate_rank CHECK (
    (relation_role='MAIN' AND candidate_rank IS NULL) OR
    (relation_role='CANDIDATE' AND candidate_rank IS NOT NULL AND candidate_rank > 0)
  ),
  ADD CONSTRAINT chk_cluster_link_type CHECK (link_type IN (
    'NEW_CLUSTER','AUTO_HIGH','MANUAL_CONFIRMED','MANUAL_REASSIGNED',
    'MERGE_MIGRATION','SPLIT_MIGRATION','REOPENED','CANDIDATE','REJECTED'
  )),
  ADD CONSTRAINT chk_cluster_review_status CHECK (
    review_status IN ('NOT_REQUIRED','PENDING','CONFIRMED','REJECTED','SKIPPED')
  ),
  ADD CONSTRAINT chk_cluster_role_status CHECK (
    (relation_role='MAIN' AND review_status <> 'PENDING') OR
    (relation_role='CANDIDATE' AND link_type='CANDIDATE')
  );

-- Previously MANUAL_REVIEW rows were incorrectly treated as current main associations.
UPDATE t_cluster_link
SET relation_role='CANDIDATE', link_type='CANDIDATE', candidate_rank=1
WHERE link_type='MANUAL_REVIEW' AND review_status='PENDING';

-- Rebuild cached statistics strictly from active MAIN associations.
UPDATE t_cluster c
LEFT JOIN (
  SELECT x.cluster_id, SUM(x.channel_count) AS total_count,
         JSON_OBJECTAGG(x.source_channel, x.channel_count) AS channels
  FROM (
    SELECT l.cluster_id, s.source_channel, COUNT(*) AS channel_count
    FROM t_cluster_link l
    JOIN t_feedback_source s ON s.id=l.feedback_source_id
    WHERE l.is_active=TRUE AND l.relation_role='MAIN'
    GROUP BY l.cluster_id, s.source_channel
  ) x
  GROUP BY x.cluster_id
) a ON a.cluster_id=c.id
SET c.total_record_count=COALESCE(a.total_count,0),
    c.channel_distribution=COALESCE(a.channels,JSON_OBJECT());

ALTER TABLE t_cluster DROP CHECK chk_cluster_status;
ALTER TABLE t_cluster ADD CONSTRAINT chk_cluster_status CHECK (
  cluster_status IN ('ACTIVE','MITIGATING','RESOLVED','REOPENED','MERGED','SPLIT_ARCHIVED','ARCHIVED')
);

