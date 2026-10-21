-- Import-governance domain consolidation. Legacy tables remain as rollback shadows.

ALTER TABLE t_import_batch
  ADD COLUMN quality_metric_version VARCHAR(30) DEFAULT NULL AFTER quality_snapshot,
  ADD COLUMN quality_total_rows INT DEFAULT NULL AFTER quality_metric_version,
  ADD COLUMN quality_completed_rows INT DEFAULT NULL AFTER quality_total_rows,
  ADD COLUMN quality_completeness_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_completed_rows,
  ADD COLUMN quality_mapping_success_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_completeness_rate,
  ADD COLUMN quality_masking_pass_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_mapping_success_rate,
  ADD COLUMN quality_duplicate_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_masking_pass_rate,
  ADD COLUMN quality_invalid_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_duplicate_rate,
  ADD COLUMN quality_attachment_available_rate DECIMAL(7,6) DEFAULT NULL AFTER quality_invalid_rate,
  ADD COLUMN quality_score DECIMAL(7,6) DEFAULT NULL AFTER quality_attachment_available_rate,
  ADD COLUMN quality_calculated_at DATETIME(3) DEFAULT NULL AFTER quality_score,
  ADD KEY idx_import_quality_time (quality_calculated_at,source_channel,id),
  ADD KEY idx_import_quality_channel (source_channel,quality_score,quality_total_rows);

UPDATE t_import_batch b
JOIN t_import_quality_snapshot q ON q.import_batch_id=b.id
SET b.quality_metric_version=q.metric_version,
    b.quality_total_rows=q.total_rows,
    b.quality_completed_rows=q.completed_rows,
    b.quality_completeness_rate=q.completeness_rate,
    b.quality_mapping_success_rate=q.mapping_success_rate,
    b.quality_masking_pass_rate=q.masking_pass_rate,
    b.quality_duplicate_rate=q.duplicate_rate,
    b.quality_invalid_rate=q.invalid_rate,
    b.quality_attachment_available_rate=q.attachment_available_rate,
    b.quality_score=q.quality_score,
    b.quality_calculated_at=q.calculated_at,
    b.quality_snapshot=JSON_MERGE_PATCH(q.metric_detail,JSON_OBJECT(
      'metricVersion',q.metric_version,'totalRows',q.total_rows,'completedRows',q.completed_rows,
      'completenessRate',q.completeness_rate,'mappingSuccessRate',q.mapping_success_rate,
      'maskingPassRate',q.masking_pass_rate,'duplicateRate',q.duplicate_rate,
      'invalidRate',q.invalid_rate,'attachmentAvailableRate',q.attachment_available_rate,
      'qualityScore',q.quality_score,'calculatedAt',q.calculated_at));

INSERT INTO t_system_audit_log(
  user_id,operation,audit_class,outcome,target_type,target_id,operation_detail,
  detail_schema_version,event_time,created_at)
SELECT h.changed_by_user_id,'FIELD_CONVERSION','BUSINESS','SUCCESS','FEEDBACK',
       CAST(h.feedback_source_id AS CHAR),
       JSON_OBJECT('legacyHistoryId',h.id,'importRowId',h.import_row_id,'fieldName',h.field_name,
         'oldValue',h.old_value,'newValue',h.new_value,'changeType',h.change_type,
         'changedByType',h.changed_by_type,'mappingVersion',h.mapping_version,
         'normalizationVersion',h.normalization_version,'changeReason',h.change_reason),
       'field-conversion-v1',h.created_at,h.created_at
FROM t_field_conversion_history h
WHERE NOT EXISTS (
  SELECT 1 FROM t_system_audit_log a
  WHERE a.operation='FIELD_CONVERSION' AND a.target_type='FEEDBACK'
    AND a.target_id=CAST(h.feedback_source_id AS CHAR) COLLATE utf8mb4_0900_as_cs
    AND JSON_UNQUOTE(JSON_EXTRACT(a.operation_detail,'$.legacyHistoryId')) COLLATE utf8mb4_0900_as_cs
        =CAST(h.id AS CHAR) COLLATE utf8mb4_0900_as_cs
);
