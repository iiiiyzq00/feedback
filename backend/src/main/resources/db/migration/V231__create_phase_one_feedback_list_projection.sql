-- 一期反馈列表兼容读模型基础设施。
-- 本迁移只创建空表；C1a 不回填、不创建 ACTIVE generation，也不接入业务写入。

CREATE TABLE t_phase_one_feedback_list_projection_generation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  generation_key VARCHAR(80) NOT NULL COMMENT '构建代次的稳定业务键',
  generation_status VARCHAR(30) NOT NULL DEFAULT 'BUILDING'
    COMMENT 'BUILDING/ACTIVE/ROLLBACK_READY/FAILED/RETIRED',
  build_cursor_source_id BIGINT DEFAULT NULL COMMENT '已完成刷新批次的最大 source_id 游标',
  expected_source_count BIGINT NOT NULL DEFAULT 0,
  projected_source_count BIGINT NOT NULL DEFAULT 0,
  rejected_source_count BIGINT NOT NULL DEFAULT 0 COMMENT '不满足安全资格或事实不完整的记录数',
  content_checksum CHAR(64) DEFAULT NULL COMMENT '未来激活前 generation 全量对账 SHA-256（十六进制）；不得以行更新时间替代',
  failure_code VARCHAR(80) DEFAULT NULL,
  failure_summary VARCHAR(500) DEFAULT NULL COMMENT '清洗后的失败摘要，不得包含业务原文或凭据',
  created_by BIGINT NOT NULL COMMENT '系统任务或人工操作人 ID 快照',
  activated_by BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  activated_at DATETIME(3) DEFAULT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  active_slot TINYINT GENERATED ALWAYS AS
    (CASE WHEN generation_status='ACTIVE' THEN 1 ELSE NULL END) STORED,
  building_slot TINYINT GENERATED ALWAYS AS
    (CASE WHEN generation_status='BUILDING' THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_phase_one_list_projection_generation_key (generation_key),
  UNIQUE KEY uk_phase_one_list_projection_active (active_slot),
  UNIQUE KEY uk_phase_one_list_projection_building (building_slot),
  KEY idx_phase_one_list_projection_generation_status (generation_status,updated_at,id),
  CONSTRAINT chk_phase_one_list_projection_generation_status CHECK
    (generation_status IN ('BUILDING','ACTIVE','ROLLBACK_READY','FAILED','RETIRED')),
  CONSTRAINT chk_phase_one_list_projection_generation_counts CHECK
    (expected_source_count >= 0 AND projected_source_count >= 0 AND rejected_source_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='一期反馈列表投影 generation 治理状态；C1a 仅建空表';

CREATE TABLE t_phase_one_feedback_list_projection (
  generation_id BIGINT NOT NULL,
  source_id BIGINT NOT NULL,
  source_channel VARCHAR(20) NOT NULL,
  source_record_id VARCHAR(100) NOT NULL,
  app_version VARCHAR(50) DEFAULT NULL,
  package_name VARCHAR(100) DEFAULT NULL,
  province_code VARCHAR(100) DEFAULT NULL,
  city_code VARCHAR(100) DEFAULT NULL,
  import_batch_id BIGINT DEFAULT NULL,
  import_batch_code VARCHAR(50) DEFAULT NULL,
  jira_import_batch_codes JSON DEFAULT NULL COMMENT 'Jira 行可能关联多个批次业务号；仅保存业务号数组',
  title VARCHAR(500) DEFAULT NULL COMMENT '已脱敏标题；为空时由应用层按历史规则回退',
  description TEXT DEFAULT NULL COMMENT '已脱敏正文；用于兼容列表标题回退和关键词过滤',
  function_module VARCHAR(50) DEFAULT NULL,
  problem_type VARCHAR(50) DEFAULT NULL,
  issue_category VARCHAR(20) DEFAULT NULL,
  severity_level VARCHAR(5) DEFAULT NULL,
  ai_severity VARCHAR(5) DEFAULT NULL,
  ai_review_status VARCHAR(20) DEFAULT NULL,
  user_sentiment VARCHAR(10) DEFAULT NULL,
  reproducibility VARCHAR(20) DEFAULT NULL,
  processing_status VARCHAR(30) NOT NULL,
  distribution_status VARCHAR(30) NOT NULL,
  owner_user_id BIGINT DEFAULT NULL,
  handoff_pending BOOLEAN NOT NULL DEFAULT FALSE,
  fault_occurred_at DATETIME(3) DEFAULT NULL,
  source_event_time DATETIME(3) DEFAULT NULL,
  reported_at DATETIME(3) DEFAULT NULL COMMENT 'fault_occurred_at/source_event_time 的兼容排序值',
  fault_time_precision VARCHAR(20) DEFAULT NULL,
  fault_time_source VARCHAR(30) DEFAULT NULL,
  fault_time_resolution VARCHAR(30) DEFAULT NULL,
  source_update_time DATETIME(3) DEFAULT NULL,
  platform_update_time DATETIME(3) DEFAULT NULL,
  ingest_time DATETIME(3) NOT NULL,
  attachment_count INT NOT NULL DEFAULT 0,
  screenshot_count INT NOT NULL DEFAULT 0,
  log_count INT NOT NULL DEFAULT 0,
  video_count INT NOT NULL DEFAULT 0,
  channel_app_id VARCHAR(100) DEFAULT NULL,
  channel_package_name VARCHAR(100) DEFAULT NULL,
  channel_device_model VARCHAR(100) DEFAULT NULL,
  channel_os_version VARCHAR(50) DEFAULT NULL,
  channel_network_type VARCHAR(30) DEFAULT NULL,
  jira_key VARCHAR(50) DEFAULT NULL,
  jira_issue_type VARCHAR(50) DEFAULT NULL,
  jira_status VARCHAR(50) DEFAULT NULL,
  jira_priority VARCHAR(20) DEFAULT NULL,
  jira_project_key VARCHAR(20) DEFAULT NULL,
  jira_assignee VARCHAR(100) DEFAULT NULL,
  jira_resolution VARCHAR(100) DEFAULT NULL,
  jira_resolved_at DATETIME(3) DEFAULT NULL,
  quality_repair_region VARCHAR(100) DEFAULT NULL,
  quality_application_type VARCHAR(50) DEFAULT NULL,
  quality_exchange_request BOOLEAN DEFAULT NULL,
  redblack_source_platform VARCHAR(50) DEFAULT NULL,
  redblack_keyword VARCHAR(200) DEFAULT NULL,
  redblack_list_type VARCHAR(50) DEFAULT NULL,
  station_product_id VARCHAR(100) DEFAULT NULL,
  station_app_id VARCHAR(100) DEFAULT NULL,
  station_source_platform VARCHAR(50) DEFAULT NULL,
  station_source_sub_channel VARCHAR(50) DEFAULT NULL,
  station_api_version VARCHAR(10) DEFAULT NULL,
  channel_meta_json JSON DEFAULT NULL COMMENT '由具名渠道事实序列化的安全元数据；不得包含外链、联系方式、密文或诊断原文',
  diagnostic_types JSON DEFAULT NULL COMMENT '可用受控诊断类型枚举数组；不保存身份值、密文或诊断正文',
  projected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (generation_id,source_id),
  KEY idx_phase_one_list_projection_reported (generation_id,reported_at,source_id),
  KEY idx_phase_one_list_projection_source_updated (generation_id,source_update_time,source_id),
  KEY idx_phase_one_list_projection_platform_updated (generation_id,platform_update_time,source_id),
  KEY idx_phase_one_list_projection_channel_reported (generation_id,source_channel,reported_at,source_id),
  KEY idx_phase_one_list_projection_scope (generation_id,source_channel,function_module,province_code,city_code),
  KEY idx_phase_one_list_projection_owner (generation_id,owner_user_id,source_id),
  CONSTRAINT fk_phase_one_list_projection_generation FOREIGN KEY (generation_id)
    REFERENCES t_phase_one_feedback_list_projection_generation(id) ON DELETE CASCADE,
  CONSTRAINT chk_phase_one_list_projection_channel CHECK
    (source_channel IN ('SDK','STATION_FEEDBACK','JIRA','QUALITY','REDBLACK')),
  CONSTRAINT chk_phase_one_list_projection_counts CHECK
    (attachment_count >= 0 AND screenshot_count >= 0 AND log_count >= 0 AND video_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='一期反馈列表安全兼容投影；每个 generation 每条来源一行';

CREATE TABLE t_phase_one_feedback_cluster_member_projection (
  generation_id BIGINT NOT NULL,
  source_id BIGINT NOT NULL,
  cluster_id BIGINT NOT NULL,
  source_channel VARCHAR(20) NOT NULL,
  function_module VARCHAR(50) DEFAULT NULL,
  province_code VARCHAR(100) DEFAULT NULL,
  city_code VARCHAR(100) DEFAULT NULL,
  projected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (generation_id,source_id),
  KEY idx_phase_one_cluster_member_cluster (generation_id,cluster_id,source_channel,source_id),
  KEY idx_phase_one_cluster_member_scope (generation_id,cluster_id,function_module,province_code,city_code),
  CONSTRAINT fk_phase_one_cluster_member_generation FOREIGN KEY (generation_id)
    REFERENCES t_phase_one_feedback_list_projection_generation(id) ON DELETE CASCADE,
  CONSTRAINT chk_phase_one_cluster_member_channel CHECK
    (source_channel IN ('SDK','STATION_FEEDBACK','JIRA','QUALITY','REDBLACK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='一期反馈活动 MAIN 问题簇成员安全投影';

-- Governed physical business table count is now 60.
