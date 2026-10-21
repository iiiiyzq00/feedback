ALTER TABLE t_sdk_ingest
  ADD COLUMN package_name VARCHAR(100) DEFAULT NULL AFTER app_version,
  ADD COLUMN function_module VARCHAR(50) DEFAULT NULL AFTER package_name,
  ADD COLUMN issue_type VARCHAR(50) DEFAULT NULL AFTER function_module,
  ADD COLUMN source_event_time DATETIME(3) DEFAULT NULL AFTER issue_type,
  ADD COLUMN os_version VARCHAR(30) DEFAULT NULL AFTER source_event_time,
  ADD COLUMN network_type VARCHAR(10) DEFAULT NULL AFTER os_version,
  ADD COLUMN trigger_type VARCHAR(20) DEFAULT NULL AFTER network_type;

ALTER TABLE t_sdk_ingest ADD KEY idx_ingest_processing (ingest_status, received_at);
