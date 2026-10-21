-- 批次 D：一期问题深度分析安全读模型。
-- 本迁移只增加空结构和校验元数据；不回填、不创建 generation、不激活或切换读模型。

ALTER TABLE t_phase_one_feedback_list_projection_generation
  ADD COLUMN analysis_projection_version SMALLINT NOT NULL DEFAULT 0
    COMMENT '问题分析投影结构版本；0 表示不可供问题分析读取' AFTER verified_member_checksum,
  ADD COLUMN verified_analysis_checksum CHAR(64) DEFAULT NULL
    COMMENT '问题分析成员事实确定性 SHA-256' AFTER analysis_projection_version,
  ADD COLUMN verified_analysis_identity_checksum CHAR(64) DEFAULT NULL
    COMMENT '问题分析身份指纹事实确定性 SHA-256' AFTER verified_analysis_checksum,
  ADD COLUMN projected_analysis_identity_count BIGINT NOT NULL DEFAULT 0
    COMMENT '当前 generation 的问题分析身份指纹行数' AFTER verified_analysis_identity_checksum,
  ADD CONSTRAINT chk_phase_one_analysis_projection_version CHECK (analysis_projection_version >= 0),
  ADD CONSTRAINT chk_phase_one_analysis_identity_count CHECK (projected_analysis_identity_count >= 0);

ALTER TABLE t_phase_one_feedback_cluster_member_projection
  ADD COLUMN logical_channel VARCHAR(24) NOT NULL DEFAULT 'LEARNING_MACHINE'
    COMMENT '一期逻辑渠道；SDK/STATION_FEEDBACK 统一为 LEARNING_MACHINE' AFTER source_channel,
  ADD COLUMN cluster_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
    COMMENT 'generation 验证时的问题簇生命周期状态' AFTER logical_channel,
  ADD COLUMN reported_at DATETIME(3) DEFAULT NULL
    COMMENT '来源语义时间；用于全历史或用户选择区间聚合' AFTER cluster_status,
  ADD COLUMN application_name VARCHAR(200) DEFAULT NULL
    COMMENT '权威渠道事实或受控字典解析的应用展示名' AFTER city_code,
  ADD COLUMN region_value VARCHAR(100) DEFAULT NULL
    COMMENT '确有来源事实的省份或质量维修区域；不得文本猜测' AFTER application_name,
  ADD COLUMN attachment_count INT NOT NULL DEFAULT 0 AFTER region_value,
  ADD COLUMN screenshot_count INT NOT NULL DEFAULT 0 AFTER attachment_count,
  ADD COLUMN log_count INT NOT NULL DEFAULT 0 AFTER screenshot_count,
  ADD COLUMN video_count INT NOT NULL DEFAULT 0 AFTER log_count,
  ADD COLUMN decision_type VARCHAR(30) DEFAULT NULL AFTER video_count,
  ADD COLUMN match_similarity DECIMAL(7,6) DEFAULT NULL AFTER decision_type,
  ADD COLUMN decision_reason VARCHAR(500) DEFAULT NULL
    COMMENT '净化后的程序归簇依据；不得包含原始敏感正文' AFTER match_similarity,
  ADD COLUMN ai_model VARCHAR(100) DEFAULT NULL AFTER decision_reason,
  ADD COLUMN ai_confidence DECIMAL(7,6) DEFAULT NULL AFTER ai_model,
  ADD COLUMN ai_action VARCHAR(30) DEFAULT NULL AFTER ai_confidence,
  ADD COLUMN ai_explanation VARCHAR(500) DEFAULT NULL
    COMMENT '净化后的模型解释；不得包含原始敏感正文或凭据' AFTER ai_action,
  ADD KEY idx_phase_one_cluster_member_reported
    (generation_id,reported_at,cluster_id,source_id),
  ADD KEY idx_phase_one_cluster_member_analysis_scope
    (generation_id,source_channel,function_module,region_value,reported_at,cluster_id),
  ADD CONSTRAINT chk_phase_one_cluster_member_logical_channel CHECK
    (logical_channel IN ('LEARNING_MACHINE','JIRA','QUALITY','REDBLACK')),
  ADD CONSTRAINT chk_phase_one_cluster_member_evidence_counts CHECK
    (attachment_count >= 0 AND screenshot_count >= 0 AND log_count >= 0 AND video_count >= 0),
  ADD CONSTRAINT chk_phase_one_cluster_member_confidences CHECK
    ((match_similarity IS NULL OR (match_similarity >= 0 AND match_similarity <= 1))
      AND (ai_confidence IS NULL OR (ai_confidence >= 0 AND ai_confidence <= 1)));

CREATE TABLE t_phase_one_feedback_cluster_identity_projection (
  generation_id BIGINT NOT NULL,
  source_id BIGINT NOT NULL,
  cluster_id BIGINT NOT NULL,
  source_channel VARCHAR(20) NOT NULL,
  logical_channel VARCHAR(24) NOT NULL,
  function_module VARCHAR(50) DEFAULT NULL,
  province_code VARCHAR(100) DEFAULT NULL,
  city_code VARCHAR(100) DEFAULT NULL,
  region_value VARCHAR(100) DEFAULT NULL,
  reported_at DATETIME(3) DEFAULT NULL,
  identity_scope VARCHAR(20) NOT NULL,
  identity_type VARCHAR(20) NOT NULL,
  fingerprint_key_id VARCHAR(50) NOT NULL,
  fingerprint BINARY(32) NOT NULL,
  projected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (generation_id,source_id,identity_scope,identity_type,fingerprint_key_id,fingerprint),
  KEY idx_phase_one_cluster_identity_count
    (generation_id,cluster_id,identity_scope,identity_type,reported_at),
  KEY idx_phase_one_cluster_identity_scope
    (generation_id,source_channel,function_module,region_value,reported_at,cluster_id),
  CONSTRAINT fk_phase_one_cluster_identity_generation FOREIGN KEY (generation_id)
    REFERENCES t_phase_one_feedback_list_projection_generation(id) ON DELETE CASCADE,
  CONSTRAINT chk_phase_one_cluster_identity_channel CHECK
    (source_channel IN ('SDK','STATION_FEEDBACK','JIRA','QUALITY','REDBLACK')),
  CONSTRAINT chk_phase_one_cluster_identity_logical_channel CHECK
    (logical_channel IN ('LEARNING_MACHINE','JIRA','QUALITY','REDBLACK')),
  CONSTRAINT chk_phase_one_cluster_identity_kind CHECK
    ((identity_scope='PERSON' AND identity_type<>'DEVICE_SN')
      OR (identity_scope='DEVICE' AND identity_type='DEVICE_SN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='一期问题分析不可逆身份指纹投影；只用于用户和设备去重统计';

-- Governed physical business table count is now 63.
