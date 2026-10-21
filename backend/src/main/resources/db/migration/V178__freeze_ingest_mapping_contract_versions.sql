ALTER TABLE t_import_batch
  ADD COLUMN enum_mapping_version VARCHAR(20) DEFAULT NULL AFTER mapping_config_json,
  ADD COLUMN enum_mapping_json JSON DEFAULT NULL AFTER enum_mapping_version;

ALTER TABLE t_sdk_ingest
  ADD COLUMN schema_version VARCHAR(30) NOT NULL DEFAULT 'sdk-form-v1' AFTER algorithm_version;
