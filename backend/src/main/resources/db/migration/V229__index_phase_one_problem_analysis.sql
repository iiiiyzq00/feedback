-- Speed up the phase-one all-history cluster aggregation without changing its facts.
CREATE INDEX idx_cluster_link_phase_one_analysis
    ON t_cluster_link(is_active, relation_role, cluster_id, feedback_source_id);
