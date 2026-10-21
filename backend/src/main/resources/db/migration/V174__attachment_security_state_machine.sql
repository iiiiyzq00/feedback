ALTER TABLE t_feedback_attachment
  ADD COLUMN integrity_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER upload_status,
  ADD COLUMN decryption_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED' AFTER integrity_status,
  ADD COLUMN malware_scan_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER decryption_status,
  ADD COLUMN content_review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER malware_scan_status,
  ADD COLUMN security_retry_count INT NOT NULL DEFAULT 0 AFTER content_review_status,
  ADD COLUMN security_next_retry_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) AFTER security_retry_count,
  ADD COLUMN security_last_error_code VARCHAR(50) DEFAULT NULL AFTER security_next_retry_at,
  ADD COLUMN security_processed_at DATETIME(3) DEFAULT NULL AFTER security_last_error_code,
  ADD CONSTRAINT chk_attachment_integrity CHECK (integrity_status IN ('PENDING','VERIFIED','FAILED')),
  ADD CONSTRAINT chk_attachment_decryption CHECK (decryption_status IN ('NOT_REQUIRED','PENDING','DECRYPTED','FAILED')),
  ADD CONSTRAINT chk_attachment_malware CHECK (malware_scan_status IN ('PENDING','CLEAN','INFECTED','ERROR')),
  ADD CONSTRAINT chk_attachment_content_review CHECK (content_review_status IN ('PENDING','PASS','REVIEW_NEEDED','REJECTED')),
  ADD KEY idx_attachment_security_claim (security_status,security_next_retry_at,id);

UPDATE t_feedback_attachment
SET integrity_status=CASE WHEN upload_status IN ('UPLOADED','VERIFIED') THEN 'VERIFIED' ELSE 'PENDING' END,
    decryption_status=CASE WHEN storage_key_id IS NULL OR source_kind='STATION_FEEDBACK' THEN 'NOT_REQUIRED' ELSE 'PENDING' END,
    malware_scan_status=CASE WHEN security_status='VERIFIED' THEN 'CLEAN' ELSE 'PENDING' END,
    content_review_status=CASE WHEN security_status='VERIFIED' THEN 'PASS' ELSE 'PENDING' END;
