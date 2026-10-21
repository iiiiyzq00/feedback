-- Consolidate mutable governance/security cases and immutable external security receipts.
ALTER TABLE t_alert_occurrence
  ADD COLUMN source_entity_id BIGINT DEFAULT NULL AFTER fact_status,
  ADD COLUMN related_entity_id BIGINT DEFAULT NULL AFTER source_entity_id,
  ADD COLUMN source_row_id BIGINT DEFAULT NULL AFTER related_entity_id,
  ADD COLUMN evidence_level VARCHAR(20) DEFAULT NULL AFTER source_row_id,
  ADD COLUMN risk_level VARCHAR(20) DEFAULT NULL AFTER evidence_level,
  ADD COLUMN source_type VARCHAR(30) DEFAULT NULL AFTER risk_level,
  ADD COLUMN fact_key CHAR(64) DEFAULT NULL AFTER source_type,
  ADD COLUMN resolution_action VARCHAR(30) DEFAULT NULL AFTER fact_key,
  ADD COLUMN resolution_note VARCHAR(500) DEFAULT NULL AFTER resolution_action,
  ADD COLUMN resolved_by BIGINT DEFAULT NULL AFTER resolution_note,
  ADD COLUMN resolved_at DATETIME(3) DEFAULT NULL AFTER resolved_by,
  ADD UNIQUE KEY uk_occurrence_type_fact_key (occurrence_type,fact_key),
  ADD UNIQUE KEY uk_occurrence_type_source_row (occurrence_type,source_row_id),
  ADD KEY idx_occurrence_case_status (occurrence_type,metric_code,fact_status,evaluation_time,id);

INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,source_entity_id,related_entity_id,fact_status,risk_level,resolution_note,resolved_by,
 resolved_at,evaluation_snapshot,evaluation_time,created_at)
SELECT NULL,'DEDUP_REVIEW',error_type,urgency_class,'t_dedup_review_event',id,feedback_source_id,
 related_source_id,review_status,IF(urgency_class='URGENT','HIGH','MEDIUM'),repair_note,repaired_by,repaired_at,
 JSON_OBJECT('confirmationNote',confirmation_note,'confirmedBy',confirmed_by,'confirmedAt',confirmed_at),
 confirmed_at,confirmed_at FROM t_dedup_review_event
ON DUPLICATE KEY UPDATE fact_status=VALUES(fact_status),resolution_note=VALUES(resolution_note),
 resolved_by=VALUES(resolved_by),resolved_at=VALUES(resolved_at);

INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,source_entity_id,related_entity_id,source_row_id,fact_status,fact_key,resolution_action,
 resolution_note,resolved_by,resolved_at,evaluation_snapshot,evaluation_time,created_at)
SELECT NULL,'IDEMPOTENCY_CONFLICT',conflict_reason,source_channel,'t_idempotency_conflict_event',id,
 existing_source_id,import_batch_id,import_row_id,conflict_status,SHA2(CONCAT(source_channel,CHAR(31),source_record_id_hash),256),resolution_action,
 resolution_note,resolved_by,resolved_at,JSON_OBJECT('importBatchId',import_batch_id,'importRowId',import_row_id,
 'existingSourceId',existing_source_id,'existingFingerprintHash',existing_fingerprint_hash,
 'incomingFingerprintHash',incoming_fingerprint_hash),occurred_at,occurred_at
FROM t_idempotency_conflict_event
ON DUPLICATE KEY UPDATE fact_status=VALUES(fact_status),resolution_action=VALUES(resolution_action),
 resolution_note=VALUES(resolution_note),resolved_by=VALUES(resolved_by),resolved_at=VALUES(resolved_at);

INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,evidence_level,risk_level,source_type,fact_status,fact_key,resolution_note,resolved_by,
 resolved_at,evaluation_snapshot,evaluation_time,created_at)
SELECT NULL,'SECURITY_SIGNAL',signal_type,evidence_level,'t_security_signal_event',id,evidence_level,
 risk_level,source_type,signal_status,SHA2(CONCAT(signal_type,CHAR(31),source_type,CHAR(31),source_ref_hash,
 CHAR(31),evidence_level),256),conclusion,concluded_by,concluded_at,
 JSON_MERGE_PATCH(evidence_snapshot,JSON_OBJECT('sourceRefHash',source_ref_hash)),occurred_at,occurred_at
FROM t_security_signal_event
ON DUPLICATE KEY UPDATE fact_status=VALUES(fact_status),risk_level=VALUES(risk_level),
 resolution_note=VALUES(resolution_note),resolved_by=VALUES(resolved_by),resolved_at=VALUES(resolved_at);

INSERT INTO t_system_audit_log(operation,audit_class,outcome,target_type,target_id,operation_detail,
 detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at)
SELECT 'EXTERNAL_SECURITY_EVENT_RECEIPT','SECURITY','SUCCESS','ALERT_INSTANCE',CAST(alert_instance_id AS CHAR),
 JSON_OBJECT('providerCode',provider_code,'externalEventId',external_event_id,'ruleCode',rule_code,
 'scopeHash',scope_hash,'payloadSha256',payload_sha256,'evidence',evidence_snapshot,
 'alertInstanceId',alert_instance_id,'incidentId',incident_id),'external-security-v1',occurred_at,
 't_external_security_event_receipt',id,CONCAT(provider_code,':',external_event_id),received_at
FROM t_external_security_event_receipt
ON DUPLICATE KEY UPDATE operation_detail=VALUES(operation_detail),event_time=VALUES(event_time);

CREATE TRIGGER trg_dedup_review_compact_insert AFTER INSERT ON t_dedup_review_event FOR EACH ROW
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,source_entity_id,related_entity_id,fact_status,risk_level,resolution_note,resolved_by,
 resolved_at,evaluation_snapshot,evaluation_time,created_at)
VALUES(NULL,'DEDUP_REVIEW',NEW.error_type,NEW.urgency_class,'t_dedup_review_event',NEW.id,
 NEW.feedback_source_id,NEW.related_source_id,NEW.review_status,IF(NEW.urgency_class='URGENT','HIGH','MEDIUM'),
 NEW.repair_note,NEW.repaired_by,NEW.repaired_at,JSON_OBJECT('confirmationNote',NEW.confirmation_note,
 'confirmedBy',NEW.confirmed_by,'confirmedAt',NEW.confirmed_at),NEW.confirmed_at,NEW.confirmed_at);
CREATE TRIGGER trg_dedup_review_compact_update AFTER UPDATE ON t_dedup_review_event FOR EACH ROW
UPDATE t_alert_occurrence SET fact_status=NEW.review_status,resolution_note=NEW.repair_note,
 resolved_by=NEW.repaired_by,resolved_at=NEW.repaired_at
WHERE legacy_source_table='t_dedup_review_event' AND legacy_source_id=OLD.id;
CREATE TRIGGER trg_dedup_review_compact_delete AFTER DELETE ON t_dedup_review_event FOR EACH ROW
DELETE FROM t_alert_occurrence WHERE legacy_source_table='t_dedup_review_event' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_idempotency_conflict_compact_insert AFTER INSERT ON t_idempotency_conflict_event FOR EACH ROW
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,source_entity_id,related_entity_id,source_row_id,fact_status,fact_key,resolution_action,
 resolution_note,resolved_by,resolved_at,evaluation_snapshot,evaluation_time,created_at)
VALUES(NULL,'IDEMPOTENCY_CONFLICT',NEW.conflict_reason,NEW.source_channel,'t_idempotency_conflict_event',NEW.id,
 NEW.existing_source_id,NEW.import_batch_id,NEW.import_row_id,NEW.conflict_status,SHA2(CONCAT(NEW.source_channel,CHAR(31),NEW.source_record_id_hash),256),
 NEW.resolution_action,NEW.resolution_note,NEW.resolved_by,NEW.resolved_at,
 JSON_OBJECT('importBatchId',NEW.import_batch_id,'importRowId',NEW.import_row_id,
 'existingSourceId',NEW.existing_source_id,'existingFingerprintHash',NEW.existing_fingerprint_hash,
 'incomingFingerprintHash',NEW.incoming_fingerprint_hash),NEW.occurred_at,NEW.occurred_at);
CREATE TRIGGER trg_idempotency_conflict_compact_update AFTER UPDATE ON t_idempotency_conflict_event FOR EACH ROW
UPDATE t_alert_occurrence SET fact_status=NEW.conflict_status,resolution_action=NEW.resolution_action,
 resolution_note=NEW.resolution_note,resolved_by=NEW.resolved_by,resolved_at=NEW.resolved_at
WHERE legacy_source_table='t_idempotency_conflict_event' AND legacy_source_id=OLD.id;
CREATE TRIGGER trg_idempotency_conflict_compact_delete AFTER DELETE ON t_idempotency_conflict_event FOR EACH ROW
DELETE FROM t_alert_occurrence WHERE legacy_source_table='t_idempotency_conflict_event' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_security_signal_compact_insert AFTER INSERT ON t_security_signal_event FOR EACH ROW
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,evidence_level,risk_level,source_type,fact_status,fact_key,resolution_note,resolved_by,
 resolved_at,evaluation_snapshot,evaluation_time,created_at)
VALUES(NULL,'SECURITY_SIGNAL',NEW.signal_type,NEW.evidence_level,'t_security_signal_event',NEW.id,
 NEW.evidence_level,NEW.risk_level,NEW.source_type,NEW.signal_status,SHA2(CONCAT(NEW.signal_type,CHAR(31),
 NEW.source_type,CHAR(31),NEW.source_ref_hash,CHAR(31),NEW.evidence_level),256),NEW.conclusion,
 NEW.concluded_by,NEW.concluded_at,JSON_MERGE_PATCH(NEW.evidence_snapshot,
 JSON_OBJECT('sourceRefHash',NEW.source_ref_hash)),NEW.occurred_at,NEW.occurred_at);
CREATE TRIGGER trg_security_signal_compact_update AFTER UPDATE ON t_security_signal_event FOR EACH ROW
UPDATE t_alert_occurrence SET fact_status=NEW.signal_status,risk_level=NEW.risk_level,
 resolution_note=NEW.conclusion,resolved_by=NEW.concluded_by,resolved_at=NEW.concluded_at
WHERE legacy_source_table='t_security_signal_event' AND legacy_source_id=OLD.id;
CREATE TRIGGER trg_security_signal_compact_delete AFTER DELETE ON t_security_signal_event FOR EACH ROW
DELETE FROM t_alert_occurrence WHERE legacy_source_table='t_security_signal_event' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_external_security_compact_insert AFTER INSERT ON t_external_security_event_receipt FOR EACH ROW
INSERT INTO t_system_audit_log(operation,audit_class,outcome,target_type,target_id,operation_detail,
 detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at)
VALUES('EXTERNAL_SECURITY_EVENT_RECEIPT','SECURITY','SUCCESS','ALERT_INSTANCE',CAST(NEW.alert_instance_id AS CHAR),
 JSON_OBJECT('providerCode',NEW.provider_code,'externalEventId',NEW.external_event_id,'ruleCode',NEW.rule_code,
 'scopeHash',NEW.scope_hash,'payloadSha256',NEW.payload_sha256,'evidence',NEW.evidence_snapshot,
 'alertInstanceId',NEW.alert_instance_id,'incidentId',NEW.incident_id),'external-security-v1',NEW.occurred_at,
 't_external_security_event_receipt',NEW.id,CONCAT(NEW.provider_code,':',NEW.external_event_id),NEW.received_at);
CREATE TRIGGER trg_external_security_compact_delete AFTER DELETE ON t_external_security_event_receipt FOR EACH ROW
DELETE FROM t_system_audit_log WHERE legacy_source_table='t_external_security_event_receipt' AND legacy_source_id=OLD.id;
