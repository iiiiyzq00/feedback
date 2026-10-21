-- D10 performance/capacity forward-only indexes.
-- Keep low-cardinality queue predicates before the join key so empty/small queues do
-- not scan the full 100k-row capacity baseline on every dashboard refresh.
ALTER TABLE t_feedback_content
    ADD KEY idx_masking_deleted_source (masking_status, is_deleted, source_id),
    ADD KEY idx_mapping_deleted_source (mapping_error(32), is_deleted, source_id);

ALTER TABLE t_cluster_link
    ADD KEY idx_link_review_active_source (review_status, is_active, feedback_source_id),
    ADD KEY idx_link_active_source (is_active, feedback_source_id);

ALTER TABLE t_feedback_ai
    ADD KEY idx_ai_processed_source (ai_processed_at, source_id);
