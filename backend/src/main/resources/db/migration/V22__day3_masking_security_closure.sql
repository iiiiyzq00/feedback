ALTER TABLE t_sdk_ingest
  ADD COLUMN body_processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER contact_allowed,
  ADD COLUMN contact_processing_status VARCHAR(20) NOT NULL DEFAULT 'NOT_PROVIDED' AFTER body_processing_status,
  ADD COLUMN urgency_class VARCHAR(20) NOT NULL DEFAULT 'NORMAL' AFTER contact_processing_status,
  ADD COLUMN next_retry_at DATETIME(3) DEFAULT NULL AFTER retry_count;

ALTER TABLE t_sdk_ingest
  ADD CONSTRAINT chk_body_processing_status CHECK (body_processing_status IN ('PENDING','PROCESSING','PASS','REVIEW_NEEDED','FAILED')),
  ADD CONSTRAINT chk_contact_processing_status CHECK (contact_processing_status IN ('NOT_PROVIDED','PENDING','PROCESSING','STORED','FAILED','DESTROYED')),
  ADD CONSTRAINT chk_ingest_urgency CHECK (urgency_class IN ('NORMAL','URGENT'));

CREATE INDEX idx_ingest_retry_due ON t_sdk_ingest(ingest_status,next_retry_at,urgency_class,received_at);

CREATE TABLE t_authorized_contact_vault (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ingest_id BIGINT NOT NULL,
  source_id BIGINT DEFAULT NULL,
  contact_type VARCHAR(20) NOT NULL,
  fingerprint BINARY(32) NOT NULL,
  fingerprint_key_id VARCHAR(50) NOT NULL,
  is_authorized BOOLEAN NOT NULL,
  contact_ciphertext VARBINARY(512) DEFAULT NULL,
  contact_nonce VARBINARY(64) DEFAULT NULL,
  contact_tag VARBINARY(64) DEFAULT NULL,
  contact_key_id VARCHAR(50) DEFAULT NULL,
  contact_algorithm VARCHAR(30) DEFAULT NULL,
  authorization_basis VARCHAR(30) DEFAULT NULL,
  authorized_at DATETIME(3) DEFAULT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_vault_ingest_contact (ingest_id,contact_type,fingerprint),
  KEY idx_vault_source (source_id,contact_type),
  KEY idx_vault_expiry (expires_at),
  CONSTRAINT chk_vault_contact_type CHECK (contact_type IN ('PHONE','EMAIL')),
  CONSTRAINT chk_vault_authorized_cipher CHECK
    ((is_authorized=FALSE AND contact_ciphertext IS NULL AND contact_nonce IS NULL AND contact_tag IS NULL
      AND contact_key_id IS NULL AND contact_algorithm IS NULL AND authorization_basis IS NULL AND authorized_at IS NULL)
     OR
     (is_authorized=TRUE AND contact_ciphertext IS NOT NULL AND contact_nonce IS NOT NULL AND contact_tag IS NOT NULL
      AND contact_key_id IS NOT NULL AND contact_algorithm IS NOT NULL AND authorization_basis='SDK_CONSENT' AND authorized_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='SDK联系方式指纹及明确授权后90天加密密文暂存区';

ALTER TABLE t_feedback_identity
  ADD COLUMN contact_authorization_basis VARCHAR(30) DEFAULT NULL AFTER contact_algorithm,
  ADD CONSTRAINT chk_contact_authorization_basis CHECK
    (contact_authorization_basis IS NULL OR contact_authorization_basis IN ('SDK_CONSENT','CHANNEL_GOVERNANCE'));

CREATE TABLE t_contact_access_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  source_id BIGINT NOT NULL,
  work_order_ref VARCHAR(100) NOT NULL,
  access_purpose VARCHAR(500) NOT NULL,
  contact_types JSON NOT NULL,
  accessed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ip_address VARCHAR(50) DEFAULT NULL,
  KEY idx_contact_access_source (source_id,accessed_at),
  KEY idx_contact_access_user (user_id,accessed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='授权联系方式解密专用不可变审计';

UPDATE t_feedback_identity
SET contact_authorization_basis='SDK_CONSENT',
    expires_at=COALESCE(expires_at,DATE_ADD(COALESCE(consented_at,created_at),INTERVAL 90 DAY))
WHERE contact_ciphertext IS NOT NULL;
