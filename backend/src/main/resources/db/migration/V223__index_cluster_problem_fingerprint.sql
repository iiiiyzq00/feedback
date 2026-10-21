-- Stable member-level exact recall. Nullable keeps existing links valid; the next
-- bounded/full reclassification naturally repopulates fingerprints via application code.
ALTER TABLE t_cluster_link
    ADD COLUMN problem_fingerprint CHAR(64) NULL AFTER feature_snapshot,
    ADD KEY idx_cluster_link_problem_fingerprint (problem_fingerprint, is_active, relation_role, cluster_id);
