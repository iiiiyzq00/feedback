-- Compatibility dual-write for callers that still write the legacy import-governance tables directly.

CREATE TRIGGER trg_import_quality_compact_insert
AFTER INSERT ON t_import_quality_snapshot FOR EACH ROW
UPDATE t_import_batch SET
  quality_metric_version=NEW.metric_version,quality_total_rows=NEW.total_rows,
  quality_completed_rows=NEW.completed_rows,quality_completeness_rate=NEW.completeness_rate,
  quality_mapping_success_rate=NEW.mapping_success_rate,quality_masking_pass_rate=NEW.masking_pass_rate,
  quality_duplicate_rate=NEW.duplicate_rate,quality_invalid_rate=NEW.invalid_rate,
  quality_attachment_available_rate=NEW.attachment_available_rate,quality_score=NEW.quality_score,
  quality_calculated_at=NEW.calculated_at,
  quality_snapshot=JSON_MERGE_PATCH(NEW.metric_detail,JSON_OBJECT(
    'metricVersion',NEW.metric_version,'totalRows',NEW.total_rows,'completedRows',NEW.completed_rows,
    'completenessRate',NEW.completeness_rate,'mappingSuccessRate',NEW.mapping_success_rate,
    'maskingPassRate',NEW.masking_pass_rate,'duplicateRate',NEW.duplicate_rate,
    'invalidRate',NEW.invalid_rate,'attachmentAvailableRate',NEW.attachment_available_rate,
    'qualityScore',NEW.quality_score,'calculatedAt',NEW.calculated_at))
WHERE id=NEW.import_batch_id;

CREATE TRIGGER trg_import_quality_compact_update
AFTER UPDATE ON t_import_quality_snapshot FOR EACH ROW
UPDATE t_import_batch SET
  quality_metric_version=NEW.metric_version,quality_total_rows=NEW.total_rows,
  quality_completed_rows=NEW.completed_rows,quality_completeness_rate=NEW.completeness_rate,
  quality_mapping_success_rate=NEW.mapping_success_rate,quality_masking_pass_rate=NEW.masking_pass_rate,
  quality_duplicate_rate=NEW.duplicate_rate,quality_invalid_rate=NEW.invalid_rate,
  quality_attachment_available_rate=NEW.attachment_available_rate,quality_score=NEW.quality_score,
  quality_calculated_at=NEW.calculated_at,
  quality_snapshot=JSON_MERGE_PATCH(NEW.metric_detail,JSON_OBJECT(
    'metricVersion',NEW.metric_version,'totalRows',NEW.total_rows,'completedRows',NEW.completed_rows,
    'completenessRate',NEW.completeness_rate,'mappingSuccessRate',NEW.mapping_success_rate,
    'maskingPassRate',NEW.masking_pass_rate,'duplicateRate',NEW.duplicate_rate,
    'invalidRate',NEW.invalid_rate,'attachmentAvailableRate',NEW.attachment_available_rate,
    'qualityScore',NEW.quality_score,'calculatedAt',NEW.calculated_at))
WHERE id=NEW.import_batch_id;

CREATE TRIGGER trg_field_conversion_compact_insert
AFTER INSERT ON t_field_conversion_history FOR EACH ROW
INSERT INTO t_system_audit_log(
  user_id,operation,audit_class,outcome,target_type,target_id,operation_detail,
  detail_schema_version,event_time,created_at)
VALUES(NEW.changed_by_user_id,'FIELD_CONVERSION','BUSINESS','SUCCESS','FEEDBACK',
  CAST(NEW.feedback_source_id AS CHAR),
  JSON_OBJECT('legacyHistoryId',NEW.id,'importRowId',NEW.import_row_id,'fieldName',NEW.field_name,
    'oldValue',NEW.old_value,'newValue',NEW.new_value,'changeType',NEW.change_type,
    'changedByType',NEW.changed_by_type,'mappingVersion',NEW.mapping_version,
    'normalizationVersion',NEW.normalization_version,'changeReason',NEW.change_reason),
  'field-conversion-v1',NEW.created_at,NEW.created_at);

