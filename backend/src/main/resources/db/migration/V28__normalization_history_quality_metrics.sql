CREATE TABLE t_data_processing_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  version_type VARCHAR(20) NOT NULL,
  scope_code VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
  version_code VARCHAR(30) NOT NULL,
  lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  rule_config JSON NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  effective_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  retired_at DATETIME(3) DEFAULT NULL,
  created_by BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  active_scope_key VARCHAR(50) GENERATED ALWAYS AS (CASE WHEN lifecycle_status='ACTIVE' THEN CONCAT(version_type,':',scope_code) ELSE NULL END) STORED,
  UNIQUE KEY uk_processing_version(version_type,scope_code,version_code),
  UNIQUE KEY uk_active_processing_version(active_scope_key),
  CONSTRAINT chk_processing_version_type CHECK (version_type IN ('MAPPING','NORMALIZATION','QUALITY')),
  CONSTRAINT chk_processing_version_status CHECK (lifecycle_status IN ('DRAFT','ACTIVE','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='映射、标准化和质量规则版本目录';

INSERT INTO t_data_processing_version(version_type,scope_code,version_code,rule_config,content_sha256) VALUES
('MAPPING','IMPORT','channel-map-v1.2',JSON_OBJECT('jiraHighest','S0','qualityExchange','HARDWARE/S1','redblackNeutral','NEUTRAL'),SHA2('channel-map-v1.2|jiraHighest=S0|qualityExchange=HARDWARE/S1|redblackNeutral=NEUTRAL',256)),
('MAPPING','SDK','sdk-envelope-v1',JSON_OBJECT('mode','DIRECT_ENVELOPE_MAPPING'),SHA2('sdk-envelope-v1|DIRECT_ENVELOPE_MAPPING',256)),
('NORMALIZATION','GLOBAL','enum-map-v2',JSON_OBJECT('canonicalization','NFKC_TRIM_UPPER_SEPARATOR_FOLD','history','FIELD_LEVEL'),SHA2('enum-map-v2|NFKC_TRIM_UPPER_SEPARATOR_FOLD|FIELD_LEVEL',256)),
('NORMALIZATION','SDK','sdk-normalize-v1',JSON_OBJECT('network','CONTROLLED_ENUM','trigger','CONTROLLED_ENUM'),SHA2('sdk-normalize-v1|network=CONTROLLED_ENUM|trigger=CONTROLLED_ENUM',256)),
('QUALITY','GLOBAL','quality-score-v1',JSON_OBJECT('recordWeights',JSON_OBJECT('completeness',0.40,'mapping',0.25,'masking',0.25,'temporal',0.10),'batchWeights',JSON_OBJECT('completeness',0.40,'mapping',0.30,'masking',0.30)),SHA2('quality-score-v1|record=.40/.25/.25/.10|batch=.40/.30/.30',256));

CREATE TABLE t_field_conversion_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  feedback_source_id BIGINT NOT NULL,
  import_row_id BIGINT DEFAULT NULL,
  field_name VARCHAR(50) NOT NULL,
  old_value VARCHAR(500) DEFAULT NULL,
  new_value VARCHAR(500) DEFAULT NULL,
  change_type VARCHAR(30) NOT NULL,
  changed_by_type VARCHAR(20) NOT NULL,
  changed_by_user_id BIGINT DEFAULT NULL,
  mapping_version VARCHAR(30) DEFAULT NULL,
  normalization_version VARCHAR(30) DEFAULT NULL,
  change_reason VARCHAR(500) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_field_history_source(feedback_source_id,created_at,id),
  KEY idx_field_history_row(import_row_id),
  CONSTRAINT chk_field_history_type CHECK (change_type IN ('AUTO_MAPPING','UNMAPPED','MANUAL_CORRECTION')),
  CONSTRAINT chk_field_history_actor CHECK (changed_by_type IN ('SYSTEM','USER')),
  CONSTRAINT chk_field_history_user CHECK ((changed_by_type='SYSTEM' AND changed_by_user_id IS NULL) OR (changed_by_type='USER' AND changed_by_user_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='真实字段转换历史，不保存正文或联系方式';

CREATE TABLE t_import_quality_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  import_batch_id BIGINT NOT NULL,
  metric_version VARCHAR(30) NOT NULL,
  total_rows INT NOT NULL,
  completed_rows INT NOT NULL,
  completeness_rate DECIMAL(7,6) NOT NULL,
  mapping_success_rate DECIMAL(7,6) NOT NULL,
  masking_pass_rate DECIMAL(7,6) NOT NULL,
  duplicate_rate DECIMAL(7,6) NOT NULL,
  invalid_rate DECIMAL(7,6) NOT NULL,
  attachment_available_rate DECIMAL(7,6) DEFAULT NULL,
  quality_score DECIMAL(7,6) NOT NULL,
  metric_detail JSON NOT NULL,
  calculated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_import_quality_batch(import_batch_id),
  CONSTRAINT chk_import_quality_ranges CHECK (
    completeness_rate BETWEEN 0 AND 1 AND mapping_success_rate BETWEEN 0 AND 1 AND
    masking_pass_rate BETWEEN 0 AND 1 AND duplicate_rate BETWEEN 0 AND 1 AND invalid_rate BETWEEN 0 AND 1 AND
    quality_score BETWEEN 0 AND 1 AND (attachment_available_rate IS NULL OR attachment_available_rate BETWEEN 0 AND 1)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='批次质量指标版本化快照';

ALTER TABLE t_feedback_content
  ADD COLUMN quality_rule_version VARCHAR(30) DEFAULT NULL AFTER data_quality_score;

UPDATE t_feedback_content SET quality_rule_version='quality-score-v1' WHERE data_quality_score IS NOT NULL;
