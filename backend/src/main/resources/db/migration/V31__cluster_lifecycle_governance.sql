-- D4-L01..L05: governed lifecycle, lineage and cluster-level optimistic concurrency.
ALTER TABLE t_cluster
  ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0 AFTER cluster_status,
  ADD COLUMN merged_into_cluster_id BIGINT DEFAULT NULL AFTER lifecycle_version,
  ADD COLUMN split_from_cluster_id BIGINT DEFAULT NULL AFTER merged_into_cluster_id,
  ADD COLUMN status_changed_by BIGINT DEFAULT NULL AFTER split_from_cluster_id,
  ADD COLUMN status_changed_at DATETIME(3) DEFAULT NULL AFTER status_changed_by,
  ADD COLUMN status_reason VARCHAR(500) DEFAULT NULL AFTER status_changed_at,
  ADD KEY idx_cluster_lineage_merge (merged_into_cluster_id),
  ADD KEY idx_cluster_lineage_split (split_from_cluster_id),
  ADD CONSTRAINT chk_cluster_lifecycle_version CHECK (lifecycle_version >= 0),
  ADD CONSTRAINT chk_cluster_merge_target CHECK (
    (cluster_status='MERGED' AND merged_into_cluster_id IS NOT NULL) OR
    (cluster_status<>'MERGED' AND merged_into_cluster_id IS NULL)
  ),
  ADD CONSTRAINT chk_cluster_terminal_evidence CHECK (
    cluster_status NOT IN ('MERGED','SPLIT_ARCHIVED') OR
    (status_changed_by IS NOT NULL AND status_changed_at IS NOT NULL AND status_reason IS NOT NULL)
  );
