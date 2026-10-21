-- Verification fixtures may share a local database, but must never enter business metrics.
ALTER TABLE t_feedback_source
  ADD COLUMN data_environment VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION' AFTER source_record_id,
  ADD COLUMN fixture_tag VARCHAR(100) DEFAULT NULL AFTER data_environment,
  ADD KEY idx_source_environment_ingest (data_environment, ingest_time, id),
  ADD CONSTRAINT chk_source_data_environment CHECK (data_environment IN ('PRODUCTION','TEST','VERIFICATION')),
  ADD CONSTRAINT chk_source_fixture_tag CHECK (
    (data_environment='PRODUCTION' AND fixture_tag IS NULL) OR
    (data_environment IN ('TEST','VERIFICATION') AND fixture_tag IS NOT NULL)
  );

-- Preserve known historical verification rows as evidence while excluding them from business views.
UPDATE t_feedback_source
SET data_environment='VERIFICATION', fixture_tag=CASE
  WHEN source_record_id LIKE 'D5C-%' THEN 'DAY5_CLUSTER_AUDIT'
  WHEN source_record_id LIKE 'D5D-%' THEN 'DAY5_DETAIL_GOVERNANCE'
  WHEN source_record_id LIKE 'D5Q-%' THEN 'DAY5_QUERY_GOVERNANCE'
  WHEN source_record_id LIKE 'D6C-%' THEN 'DAY6_CLUSTER_DETAIL'
  WHEN source_record_id LIKE 'D6L-%' THEN 'DAY6_ALERT_CENTER'
  WHEN source_record_id LIKE 'P2-DUP-REPLAY-%' THEN 'P2_DUPLICATE_REPLAY'
  ELSE fixture_tag
END
WHERE source_record_id LIKE 'D5C-%'
   OR source_record_id LIKE 'D5D-%'
   OR source_record_id LIKE 'D5Q-%'
   OR source_record_id LIKE 'D6C-%'
   OR source_record_id LIKE 'D6L-%'
   OR source_record_id LIKE 'P2-DUP-REPLAY-%';
