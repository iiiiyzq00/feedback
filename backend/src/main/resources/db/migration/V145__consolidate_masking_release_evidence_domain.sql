-- Consolidate masking release, evaluation dataset and evaluation run read models.
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,record_status,record_version,effective_from,config_desc,
 subject_user_id,secondary_user_id,is_active,created_by,created_at,updated_at)
SELECT CONCAT('MASKING_RELEASE:',id),'MASKING_RELEASE',release_version,
 JSON_OBJECT('legacyId',id,'enabledRules',enabled_rules,'evaluationAuditId',evaluation_audit_id,
   'evaluationRunId',evaluation_run_id), 'masking-release-v1',release_status,
 IF(release_status IN ('EVALUATED','PUBLISHED'),'APPROVED','PENDING'),ner_allowlist_version,
 release_status,evaluation_run_id,published_at,review_reason,evaluation_approved_by,published_by,
 release_status<>'REJECTED',created_by,created_at,COALESCE(published_at,created_at)
FROM t_masking_rule_release
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),lifecycle_status=VALUES(lifecycle_status),
 approval_status=VALUES(approval_status),scope_key=VALUES(scope_key),record_status=VALUES(record_status),
 record_version=VALUES(record_version),effective_from=VALUES(effective_from),config_desc=VALUES(config_desc),
 subject_user_id=VALUES(subject_user_id),secondary_user_id=VALUES(secondary_user_id),
 is_active=VALUES(is_active),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,config_subtype,record_status,record_version,effective_from,
 effective_to,subject_user_id,secondary_user_id,is_active,created_by,created_at,updated_at)
SELECT CONCAT('MASKING_DATASET:',id),'MASKING_DATASET',dataset_version,
 JSON_OBJECT('legacyId',id,'datasetSha256',dataset_sha256,'storageReference',storage_reference,
   'sampleCount',sample_count,'positiveCount',positive_count,'negativeCount',negative_count,
   'boundaryCount',boundary_count,'piiApprovalReference',pii_approval_reference,
   'reviewedBy',reviewed_by,'reviewedAt',reviewed_at),
 'masking-evaluation-dataset-v1',dataset_status,
 IF(dataset_status='APPROVED','APPROVED',IF(dataset_status='REJECTED','REJECTED','PENDING')),
 dataset_sha256,evidence_kind,dataset_status,sample_count,approved_at,expires_at,annotated_by,
 approved_by,dataset_status NOT IN ('REJECTED','EXPIRED'),annotated_by,created_at,
 COALESCE(approved_at,reviewed_at,created_at)
FROM t_masking_evaluation_dataset
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),lifecycle_status=VALUES(lifecycle_status),
 approval_status=VALUES(approval_status),scope_key=VALUES(scope_key),config_subtype=VALUES(config_subtype),
 record_status=VALUES(record_status),record_version=VALUES(record_version),effective_from=VALUES(effective_from),
 effective_to=VALUES(effective_to),subject_user_id=VALUES(subject_user_id),
 secondary_user_id=VALUES(secondary_user_id),is_active=VALUES(is_active),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,config_subtype,record_status,record_version,effective_from,
 subject_user_id,secondary_user_id,source_config_id,is_active,created_by,created_at,updated_at)
SELECT CONCAT('MASKING_RUN:',r.id),'MASKING_RUN',r.engine_version,
 JSON_OBJECT('legacyId',r.id,'releaseId',r.release_id,'datasetId',r.dataset_id,
   'nerTp',r.ner_tp,'nerFp',r.ner_fp,'nerFn',r.ner_fn,'endTp',r.end_tp,'endFp',r.end_fp,
   'endFn',r.end_fn,'highRiskTotal',r.high_risk_total,'highRiskMissed',r.high_risk_missed,
   'negativeUnits',r.negative_units,'falseMaskedUnits',r.false_masked_units,'nerF1',r.ner_f1,
   'endToEndF1',r.end_to_end_f1,'highRiskRecall',r.high_risk_recall,
   'falseMaskingRate',r.false_masking_rate,'executionLogSha256',r.execution_log_sha256,
   'failureSummary',r.failure_summary,'failureSummarySha256',r.failure_summary_sha256,
   'approvedAt',r.approved_at,'approvalReason',r.approval_reason),
 'masking-evaluation-run-v1',r.run_status,
 IF(r.run_status='APPROVED','APPROVED',IF(r.run_status='REJECTED','REJECTED','PENDING')),
 rel.release_version,r.gate_result,r.run_status,r.dataset_id,r.executed_at,r.executed_by,r.approved_by,
 r.release_id,r.run_status<>'REJECTED',r.executed_by,r.executed_at,COALESCE(r.approved_at,r.executed_at)
FROM t_masking_evaluation_run r JOIN t_masking_rule_release rel ON rel.id=r.release_id
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),lifecycle_status=VALUES(lifecycle_status),
 approval_status=VALUES(approval_status),scope_key=VALUES(scope_key),config_subtype=VALUES(config_subtype),
 record_status=VALUES(record_status),record_version=VALUES(record_version),effective_from=VALUES(effective_from),
 subject_user_id=VALUES(subject_user_id),secondary_user_id=VALUES(secondary_user_id),
 source_config_id=VALUES(source_config_id),is_active=VALUES(is_active),updated_at=VALUES(updated_at);

CREATE TRIGGER trg_masking_release_config_insert AFTER INSERT ON t_masking_rule_release FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,
 approval_status,scope_key,record_status,record_version,effective_from,config_desc,subject_user_id,
 secondary_user_id,is_active,created_by,created_at,updated_at)
VALUES(CONCAT('MASKING_RELEASE:',NEW.id),'MASKING_RELEASE',NEW.release_version,
 JSON_OBJECT('legacyId',NEW.id,'enabledRules',NEW.enabled_rules,'evaluationAuditId',NEW.evaluation_audit_id,
 'evaluationRunId',NEW.evaluation_run_id),'masking-release-v1',NEW.release_status,
 IF(NEW.release_status IN ('EVALUATED','PUBLISHED'),'APPROVED','PENDING'),NEW.ner_allowlist_version,
 NEW.release_status,NEW.evaluation_run_id,NEW.published_at,NEW.review_reason,NEW.evaluation_approved_by,
 NEW.published_by,NEW.release_status<>'REJECTED',NEW.created_by,NEW.created_at,COALESCE(NEW.published_at,NEW.created_at));

CREATE TRIGGER trg_masking_release_config_update AFTER UPDATE ON t_masking_rule_release FOR EACH ROW
UPDATE t_system_config SET config_version=NEW.release_version,
 config_value=JSON_OBJECT('legacyId',NEW.id,'enabledRules',NEW.enabled_rules,'evaluationAuditId',NEW.evaluation_audit_id,'evaluationRunId',NEW.evaluation_run_id),
 lifecycle_status=NEW.release_status,approval_status=IF(NEW.release_status IN ('EVALUATED','PUBLISHED'),'APPROVED','PENDING'),
 scope_key=NEW.ner_allowlist_version,record_status=NEW.release_status,record_version=NEW.evaluation_run_id,
 effective_from=NEW.published_at,config_desc=NEW.review_reason,subject_user_id=NEW.evaluation_approved_by,
 secondary_user_id=NEW.published_by,is_active=NEW.release_status<>'REJECTED',updated_at=NOW(3)
WHERE config_key=CONVERT(CONCAT('MASKING_RELEASE:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_masking_release_config_delete AFTER DELETE ON t_masking_rule_release FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONVERT(CONCAT('MASKING_RELEASE:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_masking_dataset_config_insert AFTER INSERT ON t_masking_evaluation_dataset FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,
 approval_status,scope_key,config_subtype,record_status,record_version,effective_from,effective_to,subject_user_id,
 secondary_user_id,is_active,created_by,created_at,updated_at)
VALUES(CONCAT('MASKING_DATASET:',NEW.id),'MASKING_DATASET',NEW.dataset_version,
 JSON_OBJECT('legacyId',NEW.id,'datasetSha256',NEW.dataset_sha256,'storageReference',NEW.storage_reference,
 'sampleCount',NEW.sample_count,'positiveCount',NEW.positive_count,'negativeCount',NEW.negative_count,
 'boundaryCount',NEW.boundary_count,'piiApprovalReference',NEW.pii_approval_reference,
 'reviewedBy',NEW.reviewed_by,'reviewedAt',NEW.reviewed_at),'masking-evaluation-dataset-v1',NEW.dataset_status,
 IF(NEW.dataset_status='APPROVED','APPROVED',IF(NEW.dataset_status='REJECTED','REJECTED','PENDING')),
 NEW.dataset_sha256,NEW.evidence_kind,NEW.dataset_status,NEW.sample_count,NEW.approved_at,NEW.expires_at,
 NEW.annotated_by,NEW.approved_by,NEW.dataset_status NOT IN ('REJECTED','EXPIRED'),NEW.annotated_by,
 NEW.created_at,COALESCE(NEW.approved_at,NEW.reviewed_at,NEW.created_at));

CREATE TRIGGER trg_masking_dataset_config_update AFTER UPDATE ON t_masking_evaluation_dataset FOR EACH ROW
UPDATE t_system_config SET config_version=NEW.dataset_version,
 config_value=JSON_OBJECT('legacyId',NEW.id,'datasetSha256',NEW.dataset_sha256,'storageReference',NEW.storage_reference,
 'sampleCount',NEW.sample_count,'positiveCount',NEW.positive_count,'negativeCount',NEW.negative_count,
 'boundaryCount',NEW.boundary_count,'piiApprovalReference',NEW.pii_approval_reference,'reviewedBy',NEW.reviewed_by,'reviewedAt',NEW.reviewed_at),
 lifecycle_status=NEW.dataset_status,approval_status=IF(NEW.dataset_status='APPROVED','APPROVED',IF(NEW.dataset_status='REJECTED','REJECTED','PENDING')),
 scope_key=NEW.dataset_sha256,config_subtype=NEW.evidence_kind,record_status=NEW.dataset_status,
 record_version=NEW.sample_count,effective_from=NEW.approved_at,effective_to=NEW.expires_at,
 subject_user_id=NEW.annotated_by,secondary_user_id=NEW.approved_by,
 is_active=NEW.dataset_status NOT IN ('REJECTED','EXPIRED'),updated_at=NOW(3)
WHERE config_key=CONVERT(CONCAT('MASKING_DATASET:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_masking_dataset_config_delete AFTER DELETE ON t_masking_evaluation_dataset FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONVERT(CONCAT('MASKING_DATASET:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_masking_run_config_insert AFTER INSERT ON t_masking_evaluation_run FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,
 approval_status,scope_key,config_subtype,record_status,record_version,effective_from,subject_user_id,
 secondary_user_id,source_config_id,is_active,created_by,created_at,updated_at)
SELECT CONCAT('MASKING_RUN:',NEW.id),'MASKING_RUN',NEW.engine_version,
 JSON_OBJECT('legacyId',NEW.id,'releaseId',NEW.release_id,'datasetId',NEW.dataset_id,'nerTp',NEW.ner_tp,
 'nerFp',NEW.ner_fp,'nerFn',NEW.ner_fn,'endTp',NEW.end_tp,'endFp',NEW.end_fp,'endFn',NEW.end_fn,
 'highRiskTotal',NEW.high_risk_total,'highRiskMissed',NEW.high_risk_missed,'negativeUnits',NEW.negative_units,
 'falseMaskedUnits',NEW.false_masked_units,'nerF1',NEW.ner_f1,'endToEndF1',NEW.end_to_end_f1,
 'highRiskRecall',NEW.high_risk_recall,'falseMaskingRate',NEW.false_masking_rate,
 'executionLogSha256',NEW.execution_log_sha256,'failureSummary',NEW.failure_summary,
 'failureSummarySha256',NEW.failure_summary_sha256,'approvedAt',NEW.approved_at,'approvalReason',NEW.approval_reason),
 'masking-evaluation-run-v1',NEW.run_status,
 IF(NEW.run_status='APPROVED','APPROVED',IF(NEW.run_status='REJECTED','REJECTED','PENDING')),
 release_version,NEW.gate_result,NEW.run_status,NEW.dataset_id,NEW.executed_at,NEW.executed_by,NEW.approved_by,
 NEW.release_id,NEW.run_status<>'REJECTED',NEW.executed_by,NEW.executed_at,COALESCE(NEW.approved_at,NEW.executed_at)
FROM t_masking_rule_release WHERE id=NEW.release_id;

CREATE TRIGGER trg_masking_run_config_update AFTER UPDATE ON t_masking_evaluation_run FOR EACH ROW
UPDATE t_system_config c JOIN t_masking_rule_release rel ON rel.id=NEW.release_id SET
 c.config_version=NEW.engine_version,
 c.config_value=JSON_OBJECT('legacyId',NEW.id,'releaseId',NEW.release_id,'datasetId',NEW.dataset_id,'nerTp',NEW.ner_tp,
 'nerFp',NEW.ner_fp,'nerFn',NEW.ner_fn,'endTp',NEW.end_tp,'endFp',NEW.end_fp,'endFn',NEW.end_fn,
 'highRiskTotal',NEW.high_risk_total,'highRiskMissed',NEW.high_risk_missed,'negativeUnits',NEW.negative_units,
 'falseMaskedUnits',NEW.false_masked_units,'nerF1',NEW.ner_f1,'endToEndF1',NEW.end_to_end_f1,
 'highRiskRecall',NEW.high_risk_recall,'falseMaskingRate',NEW.false_masking_rate,
 'executionLogSha256',NEW.execution_log_sha256,'failureSummary',NEW.failure_summary,
 'failureSummarySha256',NEW.failure_summary_sha256,'approvedAt',NEW.approved_at,'approvalReason',NEW.approval_reason),
 c.lifecycle_status=NEW.run_status,c.approval_status=IF(NEW.run_status='APPROVED','APPROVED',IF(NEW.run_status='REJECTED','REJECTED','PENDING')),
 c.scope_key=rel.release_version,c.config_subtype=NEW.gate_result,c.record_status=NEW.run_status,
 c.record_version=NEW.dataset_id,c.effective_from=NEW.executed_at,c.subject_user_id=NEW.executed_by,
 c.secondary_user_id=NEW.approved_by,c.source_config_id=NEW.release_id,c.is_active=NEW.run_status<>'REJECTED',c.updated_at=NOW(3)
WHERE c.config_key=CONVERT(CONCAT('MASKING_RUN:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_masking_run_config_delete AFTER DELETE ON t_masking_evaluation_run FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONVERT(CONCAT('MASKING_RUN:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;
