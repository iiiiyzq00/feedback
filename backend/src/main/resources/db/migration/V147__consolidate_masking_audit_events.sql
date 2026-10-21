-- Consolidate append-only masking review, evaluation and rule-change evidence.
INSERT INTO t_system_audit_log(user_id,operation,audit_class,outcome,target_type,target_id,
 correlation_id,operation_detail,detail_schema_version,event_time,legacy_source_table,
 legacy_source_id,idempotency_key,created_at)
SELECT reviewer,CONCAT('MASKING_',audit_type),'SECURITY',
 IF(review_conclusion='REJECTED','DENIED','SUCCESS'),'MASKING_RULE_VERSION',masking_rule_version,
 evaluation_key,
 JSON_OBJECT('legacyId',id,'auditType',audit_type,'reviewConclusion',review_conclusion,
   'precision',eval_precision,'recall',eval_recall,'f1Score',eval_f1_score,
   'nerF1Score',ner_f1_score,'highRiskRecall',high_risk_recall,
   'falseMaskingRate',false_masking_rate,'passedSamples',eval_passed_samples,
   'totalSamples',eval_total_samples,'errorDetails',eval_error_details),
 'masking-audit-v1',created_at,'t_masking_audit',id,CONCAT('masking-audit:',id),created_at
FROM t_masking_audit;

CREATE TRIGGER trg_masking_audit_compact_insert AFTER INSERT ON t_masking_audit FOR EACH ROW
INSERT INTO t_system_audit_log(user_id,operation,audit_class,outcome,target_type,target_id,
 correlation_id,operation_detail,detail_schema_version,event_time,legacy_source_table,
 legacy_source_id,idempotency_key,created_at)
VALUES(NEW.reviewer,CONCAT('MASKING_',NEW.audit_type),'SECURITY',
 IF(NEW.review_conclusion='REJECTED','DENIED','SUCCESS'),'MASKING_RULE_VERSION',NEW.masking_rule_version,
 NEW.evaluation_key,
 JSON_OBJECT('legacyId',NEW.id,'auditType',NEW.audit_type,'reviewConclusion',NEW.review_conclusion,
   'precision',NEW.eval_precision,'recall',NEW.eval_recall,'f1Score',NEW.eval_f1_score,
   'nerF1Score',NEW.ner_f1_score,'highRiskRecall',NEW.high_risk_recall,
   'falseMaskingRate',NEW.false_masking_rate,'passedSamples',NEW.eval_passed_samples,
   'totalSamples',NEW.eval_total_samples,'errorDetails',NEW.eval_error_details),
 'masking-audit-v1',NEW.created_at,'t_masking_audit',NEW.id,CONCAT('masking-audit:',NEW.id),NEW.created_at);
