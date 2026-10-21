ALTER TABLE t_feedback_source DROP CHECK chk_source_channel;
ALTER TABLE t_feedback_source
  ADD CONSTRAINT chk_source_channel
  CHECK (source_channel IN ('SDK','JIRA','QUALITY','REDBLACK','STATION_FEEDBACK'));

CREATE TABLE t_channel_station_feedback (
  source_id BIGINT PRIMARY KEY COMMENT '一对一关联 t_feedback_source.id',
  receipt_id BINARY(16) NOT NULL COMMENT '兼容入口受理回执',
  source_api_version VARCHAR(10) NOT NULL COMMENT 'V1/V2',
  product_id VARCHAR(100) NOT NULL,
  source_app_id VARCHAR(100) DEFAULT NULL,
  source_app_name VARCHAR(200) DEFAULT NULL,
  source_user_id VARCHAR(100) DEFAULT NULL COMMENT '来源请求用户标识，不作为可信认证身份',
  authenticated_user_id VARCHAR(100) DEFAULT NULL COMMENT '网关认证身份快照',
  source_platform VARCHAR(50) NOT NULL,
  source_feedback_type VARCHAR(50) NOT NULL,
  source_sub_channel VARCHAR(50) NOT NULL,
  source_description VARCHAR(1000) DEFAULT NULL,
  feedback_attributes JSON DEFAULT NULL COMMENT '待确认结构的动态属性，禁止直接进入普通出口',
  attribute_schema_version VARCHAR(30) NOT NULL DEFAULT 'UNRESOLVED_V1',
  attribute_mapping_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  attribute_payload_hash BINARY(32) DEFAULT NULL,
  pending_photo_urls JSON DEFAULT NULL COMMENT '附件 Worker 接管前的受限 URL 清单',
  source_phone_ciphertext VARCHAR(1024) DEFAULT NULL COMMENT 'V2 旧协议密文，处理完成后必须清除',
  contact_status VARCHAR(30) NOT NULL DEFAULT 'NOT_PROCESSED',
  ingest_status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
  processing_stage VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
  retry_count INT NOT NULL DEFAULT 0,
  max_retry_count INT NOT NULL DEFAULT 5,
  next_retry_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  lease_owner VARCHAR(100) DEFAULT NULL,
  lease_expires_at DATETIME(3) DEFAULT NULL,
  last_error_code VARCHAR(50) DEFAULT NULL,
  last_error_summary VARCHAR(500) DEFAULT NULL,
  request_fingerprint BINARY(32) NOT NULL,
  processed_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_station_receipt (receipt_id),
  KEY idx_station_claim (ingest_status,next_retry_at,lease_expires_at,source_id),
  KEY idx_station_product_time (product_id,created_at,source_id),
  CONSTRAINT fk_station_feedback_source FOREIGN KEY (source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT chk_station_api_version CHECK (source_api_version IN ('V1','V2')),
  CONSTRAINT chk_station_attribute_status CHECK (attribute_mapping_status IN ('PENDING','MAPPED','REVIEW_NEEDED')),
  CONSTRAINT chk_station_contact_status CHECK (contact_status IN ('NOT_REQUESTED','NOT_PROVIDED','NOT_PROCESSED','PROCESSING','ENCRYPTED','REVIEW_NEEDED')),
  CONSTRAINT chk_station_ingest_status CHECK (ingest_status IN ('RECEIVED','PROCESSING','RETRY_PENDING','REVIEW_NEEDED','COMPLETED','FAILED_FINAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='Station反馈实时渠道独有字段与持久化处理状态';

-- Governed physical business table count is now 43.
