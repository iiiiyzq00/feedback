-- Identity and diagnostic facts required for fault investigation are retained as encrypted,
-- purpose-bound facts. Transport/raw envelopes keep their separate privacy-review lifecycle.
ALTER TABLE t_channel_sdk
  ADD COLUMN diagnostic_ciphertext MEDIUMBLOB DEFAULT NULL AFTER private_file_manifest,
  ADD COLUMN diagnostic_nonce VARBINARY(64) DEFAULT NULL AFTER diagnostic_ciphertext,
  ADD COLUMN diagnostic_tag VARBINARY(64) DEFAULT NULL AFTER diagnostic_nonce,
  ADD COLUMN diagnostic_key_id VARCHAR(50) DEFAULT NULL AFTER diagnostic_tag,
  ADD COLUMN diagnostic_algorithm VARCHAR(30) DEFAULT NULL AFTER diagnostic_key_id,
  ADD COLUMN diagnostic_types VARCHAR(255) DEFAULT NULL AFTER diagnostic_algorithm;

UPDATE t_feedback_identity
SET expires_at='2099-12-31 23:59:59.000'
WHERE contact_ciphertext IS NOT NULL AND (expires_at IS NULL OR expires_at<'2099-12-31 23:59:59.000');
