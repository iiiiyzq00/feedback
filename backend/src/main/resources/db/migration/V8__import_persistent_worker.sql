ALTER TABLE t_import_batch
  ADD COLUMN mapping_config_json JSON DEFAULT NULL AFTER mapping_config_version,
  ADD COLUMN lease_holder VARCHAR(100) DEFAULT NULL AFTER batch_status,
  ADD COLUMN lease_expires_at DATETIME(3) DEFAULT NULL AFTER lease_holder,
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER lease_expires_at,
  ADD COLUMN next_retry_at DATETIME(3) DEFAULT NULL AFTER retry_count,
  ADD COLUMN last_error_code VARCHAR(50) DEFAULT NULL AFTER next_retry_at,
  ADD COLUMN last_error_detail VARCHAR(500) DEFAULT NULL AFTER last_error_code,
  ADD KEY idx_import_batch_worker (batch_status, next_retry_at, lease_expires_at, created_at);

ALTER TABLE t_import_batch
  ADD CONSTRAINT chk_import_batch_retry_count CHECK (retry_count >= 0),
  ADD CONSTRAINT chk_import_batch_mapping CHECK (batch_status <> 'PROCESSING' OR mapping_config_json IS NOT NULL);
