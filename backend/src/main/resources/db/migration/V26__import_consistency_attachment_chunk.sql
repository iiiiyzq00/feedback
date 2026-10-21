ALTER TABLE t_import_batch
  ADD COLUMN nonempty_headers_json JSON DEFAULT NULL AFTER mapping_config_json,
  ADD COLUMN reconciliation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER reviewed_rows,
  ADD CONSTRAINT chk_import_reconciliation_status CHECK (reconciliation_status IN ('PENDING','MATCHED','MISMATCH'));

ALTER TABLE t_import_row
  ADD COLUMN slice_no INT DEFAULT NULL AFTER `row_number`,
  ADD KEY idx_import_slice(batch_id,slice_no,row_status);

ALTER TABLE t_import_attachment_task
  ADD COLUMN url_expires_at DATETIME(3) DEFAULT NULL AFTER source_url_algorithm,
  ADD COLUMN next_retry_at DATETIME(3) DEFAULT NULL AFTER retry_count,
  ADD COLUMN final_status VARCHAR(20) DEFAULT NULL AFTER task_status,
  ADD COLUMN expected_sha256 BINARY(32) DEFAULT NULL AFTER attachment_type,
  ADD COLUMN expected_size_bytes BIGINT DEFAULT NULL AFTER expected_sha256,
  ADD CONSTRAINT chk_import_attach_size CHECK (expected_size_bytes IS NULL OR expected_size_bytes BETWEEN 1 AND 209715200);

CREATE TABLE t_import_upload_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  file_id BINARY(16) NOT NULL,
  source_channel VARCHAR(20) NOT NULL,
  total_chunks INT NOT NULL,
  total_size_bytes BIGINT NOT NULL DEFAULT 0,
  upload_status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING',
  expires_at DATETIME(3) NOT NULL,
  created_by BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_import_upload_file(file_id),
  KEY idx_import_upload_expiry(upload_status,expires_at),
  CONSTRAINT chk_import_upload_channel CHECK (source_channel IN ('JIRA','QUALITY','REDBLACK')),
  CONSTRAINT chk_import_upload_chunks CHECK (total_chunks BETWEEN 1 AND 1000),
  CONSTRAINT chk_import_upload_status CHECK (upload_status IN ('UPLOADING','ASSEMBLING','COMPLETED','FAILED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='导入加密分片会话';

CREATE TABLE t_import_upload_chunk (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  chunk_sha256 BINARY(32) NOT NULL,
  chunk_size_bytes INT NOT NULL,
  chunk_ciphertext MEDIUMBLOB NOT NULL,
  chunk_nonce VARBINARY(64) NOT NULL,
  chunk_tag VARBINARY(64) NOT NULL,
  chunk_key_id VARCHAR(50) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_import_upload_chunk(session_id,chunk_index),
  CONSTRAINT chk_import_chunk_size CHECK (chunk_size_bytes BETWEEN 1 AND 5242880)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='导入加密分片';

ALTER TABLE t_feedback_attachment
  ADD COLUMN security_status VARCHAR(20) NOT NULL DEFAULT 'QUARANTINED' AFTER upload_status,
  ADD COLUMN source_kind VARCHAR(20) NOT NULL DEFAULT 'SDK' AFTER security_status,
  ADD CONSTRAINT chk_attachment_security_status CHECK (security_status IN ('QUARANTINED','VERIFIED','REJECTED')),
  ADD CONSTRAINT chk_attachment_source_kind CHECK (source_kind IN ('SDK','IMPORT'));

UPDATE t_feedback_attachment SET security_status='VERIFIED',source_kind='SDK' WHERE upload_status IN ('UPLOADED','VERIFIED');
