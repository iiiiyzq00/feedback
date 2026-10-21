-- Consolidate immutable cluster-review decisions into typed alert occurrence facts.
INSERT INTO t_alert_occurrence(occurrence_type,metric_code,metric_value,dimension_key,
 legacy_source_table,legacy_source_id,sample_count,fact_status,source_entity_id,related_entity_id,
 fact_key,resolution_note,resolved_by,resolved_at,evaluation_snapshot,evaluation_time,created_at)
SELECT 'CLUSTER_REVIEW_DECISION',decision_type,is_overturn,request_id,
 't_cluster_review_decision_event',id,candidate_count,decision_type,feedback_source_id,
 selected_cluster_id,SHA2(request_id,256),reason,reviewer_id,decided_at,
 JSON_OBJECT('requestId',request_id,'topCandidateClusterId',top_candidate_cluster_id,
   'selectedClusterId',selected_cluster_id,'isOverturn',is_overturn),decided_at,decided_at
FROM t_cluster_review_decision_event;

CREATE TRIGGER trg_cluster_review_decision_compact_insert
AFTER INSERT ON t_cluster_review_decision_event FOR EACH ROW
INSERT INTO t_alert_occurrence(occurrence_type,metric_code,metric_value,dimension_key,
 legacy_source_table,legacy_source_id,sample_count,fact_status,source_entity_id,related_entity_id,
 fact_key,resolution_note,resolved_by,resolved_at,evaluation_snapshot,evaluation_time,created_at)
VALUES('CLUSTER_REVIEW_DECISION',NEW.decision_type,NEW.is_overturn,NEW.request_id,
 't_cluster_review_decision_event',NEW.id,NEW.candidate_count,NEW.decision_type,
 NEW.feedback_source_id,NEW.selected_cluster_id,SHA2(NEW.request_id,256),NEW.reason,
 NEW.reviewer_id,NEW.decided_at,
 JSON_OBJECT('requestId',NEW.request_id,'topCandidateClusterId',NEW.top_candidate_cluster_id,
   'selectedClusterId',NEW.selected_cluster_id,'isOverturn',NEW.is_overturn),
 NEW.decided_at,NEW.decided_at);

UPDATE t_alert_data_contract
SET schema_version='cluster-review-decision-v2',source_system='t_alert_occurrence'
WHERE contract_code='cluster_review_decision';
