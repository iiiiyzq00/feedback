-- SDK attachments have already passed encrypted-file hash verification and FBA2
-- decryption at this point. Normalize the legacy upload state so the fail-closed
-- malware/content scanner can claim them; the scanner still decides whether an
-- attachment is released, reviewed, or rejected.
UPDATE t_feedback_attachment
SET upload_status = 'VERIFIED',
    security_next_retry_at = COALESCE(security_next_retry_at, CURRENT_TIMESTAMP(3))
WHERE source_kind = 'SDK'
  AND upload_status = 'UPLOADED'
  AND integrity_status = 'VERIFIED'
  AND decryption_status = 'DECRYPTED'
  AND security_status = 'QUARANTINED'
  AND deleted_at IS NULL;
