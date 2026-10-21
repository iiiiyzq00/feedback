CREATE TABLE t_station_feedback_raw (
  source_id BIGINT PRIMARY KEY,
  payload_ciphertext MEDIUMBLOB DEFAULT NULL,
  payload_nonce VARBINARY(64) DEFAULT NULL,
  payload_tag VARBINARY(64) DEFAULT NULL,
  key_id VARCHAR(50) NOT NULL,
  algorithm VARCHAR(30) NOT NULL,
  retention_until DATETIME(3) NOT NULL,
  deleted_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_station_raw_source FOREIGN KEY (source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT chk_station_raw_algorithm CHECK (algorithm='AES-256-GCM'),
  CONSTRAINT chk_station_raw_lifecycle CHECK (
    (deleted_at IS NULL AND payload_ciphertext IS NOT NULL AND payload_nonce IS NOT NULL AND payload_tag IS NOT NULL)
    OR (deleted_at IS NOT NULL AND payload_ciphertext IS NULL AND payload_nonce IS NULL AND payload_tag IS NULL)
  ),
  KEY idx_station_raw_retention (retention_until,deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='Station原始请求加密信封；仅供授权隐私复核，不进入普通业务查询';

-- Governed physical business table count is now 45.
