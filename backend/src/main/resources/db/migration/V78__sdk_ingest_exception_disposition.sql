ALTER TABLE t_sdk_ingest
  ADD COLUMN disposition_status VARCHAR(20) NOT NULL DEFAULT 'OPEN' AFTER ingest_status,
  ADD COLUMN disposition_reason VARCHAR(50) DEFAULT NULL AFTER disposition_status,
  ADD COLUMN disposition_note VARCHAR(500) DEFAULT NULL AFTER disposition_reason,
  ADD COLUMN disposition_by BIGINT DEFAULT NULL AFTER disposition_note,
  ADD COLUMN disposition_at DATETIME(3) DEFAULT NULL AFTER disposition_by,
  ADD CONSTRAINT chk_sdk_disposition_status CHECK (disposition_status IN ('OPEN','RETRYING','CLOSED','AUTO_ARCHIVED','RECOVERED')),
  ADD KEY idx_sdk_disposition(disposition_status,received_at);

UPDATE t_sdk_ingest
SET disposition_status = CASE
      WHEN ingest_status='FAILED' AND last_error_code IN ('AEADBadTagException','IllegalBlockSizeException','InvalidKeyException') THEN 'CLOSED'
      ELSE 'OPEN' END,
    disposition_reason = CASE
      WHEN ingest_status='FAILED' AND last_error_code IN ('AEADBadTagException','IllegalBlockSizeException','InvalidKeyException') THEN 'KEY_OR_CIPHERTEXT_INCOMPATIBLE'
      ELSE NULL END,
    disposition_note = CASE
      WHEN ingest_status='FAILED' AND last_error_code IN ('AEADBadTagException','IllegalBlockSizeException','InvalidKeyException') THEN '历史密文无法使用当前密钥体系解密；重复处理无法恢复，需客户端使用新的反馈ID重新提交'
      ELSE NULL END,
    disposition_at = CASE
      WHEN ingest_status='FAILED' AND last_error_code IN ('AEADBadTagException','IllegalBlockSizeException','InvalidKeyException') THEN NOW(3)
      ELSE NULL END;
