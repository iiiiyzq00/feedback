ALTER TABLE t_feedback_attachment
    ADD COLUMN log_preview_ciphertext MEDIUMBLOB NULL
        COMMENT 'AES-GCM encrypted bounded text preview; never stores plaintext' AFTER content_review_status,
    ADD COLUMN log_preview_key_id VARCHAR(100) NULL
        COMMENT 'Encryption key identifier for log_preview_ciphertext' AFTER log_preview_ciphertext,
    ADD COLUMN log_preview_plaintext_bytes INT NULL
        COMMENT 'Plaintext preview byte count before encryption' AFTER log_preview_key_id,
    ADD COLUMN log_preview_status VARCHAR(20) NULL
        COMMENT 'PENDING, AVAILABLE, or FAILED' AFTER log_preview_plaintext_bytes,
    ADD COLUMN log_preview_error_code VARCHAR(100) NULL
        COMMENT 'Stable failure code without sensitive content' AFTER log_preview_status;

CREATE INDEX idx_attachment_log_preview_backfill
    ON t_feedback_attachment(attachment_type, security_status, log_preview_status, id);
