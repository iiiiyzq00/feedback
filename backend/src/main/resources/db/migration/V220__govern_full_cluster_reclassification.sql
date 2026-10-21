-- Full problem-cluster reclassification is an explicit, auditable operation.
-- This migration only creates the durable run/snapshot facts; it never starts a run.
CREATE TABLE t_cluster_reclassification_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL,
  request_id CHAR(36) NOT NULL,
  run_status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
  reason VARCHAR(500) NOT NULL,
  requested_by BIGINT DEFAULT NULL,
  snapshot_safe_count BIGINT NOT NULL DEFAULT 0,
  processed_count BIGINT NOT NULL DEFAULT 0,
  linked_existing_count BIGINT NOT NULL DEFAULT 0,
  new_cluster_count BIGINT NOT NULL DEFAULT 0,
  error_detail VARCHAR(500) DEFAULT NULL,
  lease_holder VARCHAR(100) DEFAULT NULL,
  lease_expires_at DATETIME(3) DEFAULT NULL,
  started_at DATETIME(3) DEFAULT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  rolled_back_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_cluster_reclassification_public (public_id),
  UNIQUE KEY uk_cluster_reclassification_request (request_id),
  KEY idx_cluster_reclassification_claim (run_status,lease_expires_at,id),
  KEY idx_cluster_reclassification_created (created_at,id),
  CONSTRAINT chk_cluster_reclassification_status CHECK (
    run_status IN ('REQUESTED','RUNNING','FINALIZING','COMPLETED','FAILED','ROLLING_BACK','ROLLED_BACK')
  ),
  CONSTRAINT chk_cluster_reclassification_counts CHECK (
    snapshot_safe_count >= 0 AND processed_count >= 0 AND linked_existing_count >= 0 AND new_cluster_count >= 0
  ),
  CONSTRAINT chk_cluster_reclassification_reason CHECK (CHAR_LENGTH(reason) BETWEEN 3 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='全量问题簇重分类运行事实';

CREATE TABLE t_cluster_reclassification_cluster_snapshot (
  run_id BIGINT NOT NULL,
  cluster_id BIGINT NOT NULL,
  original_status VARCHAR(20) NOT NULL,
  original_status_reason VARCHAR(500) DEFAULT NULL,
  original_status_changed_by BIGINT DEFAULT NULL,
  original_status_changed_at DATETIME(3) DEFAULT NULL,
  original_lifecycle_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (run_id,cluster_id),
  KEY idx_cluster_reclassification_snapshot_cluster (cluster_id,run_id),
  CONSTRAINT fk_cluster_reclassification_snapshot_run FOREIGN KEY (run_id)
    REFERENCES t_cluster_reclassification_run(id),
  CONSTRAINT fk_cluster_reclassification_snapshot_cluster FOREIGN KEY (cluster_id)
    REFERENCES t_cluster(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='全量问题簇重分类前簇状态快照';

ALTER TABLE t_cluster_link
  ADD COLUMN reclassification_run_id BIGINT DEFAULT NULL AFTER decision_reason,
  ADD COLUMN superseded_by_reclassification_run_id BIGINT DEFAULT NULL AFTER reclassification_run_id,
  ADD KEY idx_cluster_link_reclassification_run (reclassification_run_id,relation_role,is_active),
  ADD KEY idx_cluster_link_reclassification_superseded (superseded_by_reclassification_run_id,relation_role,is_active),
  ADD CONSTRAINT fk_cluster_link_reclassification_run FOREIGN KEY (reclassification_run_id)
    REFERENCES t_cluster_reclassification_run(id),
  ADD CONSTRAINT fk_cluster_link_reclassification_superseded FOREIGN KEY (superseded_by_reclassification_run_id)
    REFERENCES t_cluster_reclassification_run(id);
