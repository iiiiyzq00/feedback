ALTER TABLE t_feedback_attachment
    ADD COLUMN media_preview_status VARCHAR(20) NULL
        COMMENT 'PENDING, PROCESSING, AVAILABLE, or FAILED' AFTER log_preview_error_code,
    ADD COLUMN media_preview_object_key VARCHAR(1024) NULL
        COMMENT 'Private derived preview object key; never exposed to clients' AFTER media_preview_status,
    ADD COLUMN media_preview_object_version VARCHAR(255) NULL AFTER media_preview_object_key,
    ADD COLUMN media_preview_object_etag VARCHAR(255) NULL AFTER media_preview_object_version,
    ADD COLUMN media_preview_bytes BIGINT NULL AFTER media_preview_object_etag,
    ADD COLUMN media_preview_width INT NULL AFTER media_preview_bytes,
    ADD COLUMN media_preview_height INT NULL AFTER media_preview_width,
    ADD COLUMN media_preview_attempts INT NOT NULL DEFAULT 0 AFTER media_preview_height,
    ADD COLUMN media_preview_next_retry_at DATETIME(3) NULL AFTER media_preview_attempts,
    ADD COLUMN media_preview_error_code VARCHAR(100) NULL AFTER media_preview_next_retry_at,
    ADD CONSTRAINT chk_attachment_media_preview_status CHECK
        (media_preview_status IS NULL OR media_preview_status IN ('PENDING','PROCESSING','AVAILABLE','FAILED')),
    ADD CONSTRAINT chk_attachment_media_preview_shape CHECK
        ((media_preview_status = 'AVAILABLE'
          AND media_preview_object_key IS NOT NULL
          AND media_preview_bytes > 0
          AND media_preview_width > 0
          AND media_preview_height > 0)
         OR media_preview_status <> 'AVAILABLE'
         OR media_preview_status IS NULL),
    ADD KEY idx_attachment_media_preview_work
        (media_preview_status, media_preview_next_retry_at, id);

-- Existing verified media are queued for bounded background backfill; no source upload is repeated.
UPDATE t_feedback_attachment
SET media_preview_status = 'PENDING',
    media_preview_next_retry_at = CURRENT_TIMESTAMP(3)
WHERE attachment_type IN ('IMAGE','SCREENSHOT','ANNOTATED_SCREENSHOT','VIDEO','SCREEN_RECORDING')
  AND security_status = 'VERIFIED'
  AND integrity_status = 'VERIFIED'
  AND malware_scan_status = 'CLEAN'
  AND content_review_status = 'PASS'
  AND deleted_at IS NULL
  AND media_preview_status IS NULL;
