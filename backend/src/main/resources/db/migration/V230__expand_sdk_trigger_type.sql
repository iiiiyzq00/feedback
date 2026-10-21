-- Preserve descriptive SDK trigger identifiers without truncating them to the legacy 20-character boundary.
ALTER TABLE t_sdk_ingest
  MODIFY COLUMN trigger_type VARCHAR(64) DEFAULT NULL;

ALTER TABLE t_channel_sdk
  MODIFY COLUMN trigger_type VARCHAR(64) DEFAULT NULL;
