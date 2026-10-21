CREATE TABLE t_application_identity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL COMMENT 'UUID，对外稳定应用身份',
  display_name VARCHAR(200) NOT NULL COMMENT '应用展示名，不作为身份合并依据',
  lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RETIRED',
  mapping_version BIGINT NOT NULL DEFAULT 1 COMMENT 'alias 映射集合 CAS 版本',
  created_by BIGINT DEFAULT NULL COMMENT '自动建档为空，人工操作为用户ID快照',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_application_identity_public_id (public_id),
  KEY idx_application_identity_cursor (public_id,id),
  CONSTRAINT chk_application_identity_lifecycle CHECK (lifecycle_status IN ('ACTIVE','RETIRED')),
  CONSTRAINT chk_application_identity_version CHECK (mapping_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='跨渠道应用身份主数据';

CREATE TABLE t_application_identity_alias (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  alias_public_id BINARY(16) NOT NULL COMMENT '跨版本稳定 alias UUID',
  source_channel VARCHAR(20) NOT NULL COMMENT 'SDK/STATION_FEEDBACK',
  alias_type VARCHAR(30) NOT NULL COMMENT 'SDK_APP_ID/SDK_PACKAGE_NAME/STATION_SOURCE_APP_ID',
  normalized_alias VARCHAR(200) NOT NULL COMMENT 'NFKC、去首尾空白后的别名；包名额外转小写',
  active_alias_key BINARY(32) DEFAULT NULL COMMENT '活动 alias 唯一键 SHA-256；历史版本为空',
  application_identity_id BIGINT NOT NULL COMMENT '关联 t_application_identity.id',
  alias_version BIGINT NOT NULL COMMENT '同一 alias_public_id 的递增 CAS 版本',
  binding_status VARCHAR(20) NOT NULL COMMENT 'ACTIVE/SUPERSEDED',
  evidence_type VARCHAR(30) NOT NULL COMMENT 'AUTO_SAME_SOURCE/MANUAL_REBIND',
  previous_binding_id BIGINT DEFAULT NULL COMMENT '前序绑定行，软关联本表 id',
  request_id CHAR(36) DEFAULT NULL COMMENT '人工重绑 UUID 幂等请求号',
  command_fingerprint BINARY(32) DEFAULT NULL COMMENT '人工重绑强类型输入 SHA-256',
  target_mapping_version_snapshot BIGINT DEFAULT NULL COMMENT '人工重绑完成后的目标 identity 版本，用于稳定重放',
  changed_by BIGINT DEFAULT NULL COMMENT '人工操作者用户ID快照',
  change_reason VARCHAR(500) DEFAULT NULL COMMENT '人工重绑业务原因',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_application_alias_active_key (active_alias_key),
  UNIQUE KEY uk_application_alias_version (alias_public_id,alias_version),
  UNIQUE KEY uk_application_alias_request (request_id),
  KEY idx_application_alias_identity (application_identity_id,binding_status,id),
  KEY idx_application_alias_lookup (source_channel,alias_type,normalized_alias,binding_status),
  CONSTRAINT fk_application_alias_identity FOREIGN KEY (application_identity_id) REFERENCES t_application_identity(id),
  CONSTRAINT chk_application_alias_channel CHECK (source_channel IN ('SDK','STATION_FEEDBACK')),
  CONSTRAINT chk_application_alias_type CHECK (alias_type IN ('SDK_APP_ID','SDK_PACKAGE_NAME','STATION_SOURCE_APP_ID')),
  CONSTRAINT chk_application_alias_status CHECK (binding_status IN ('ACTIVE','SUPERSEDED')),
  CONSTRAINT chk_application_alias_evidence CHECK (evidence_type IN ('AUTO_SAME_SOURCE','MANUAL_REBIND')),
  CONSTRAINT chk_application_alias_version CHECK (alias_version > 0),
  CONSTRAINT chk_application_alias_active_key CHECK (
    (binding_status='ACTIVE' AND active_alias_key IS NOT NULL)
    OR (binding_status='SUPERSEDED' AND active_alias_key IS NULL)
  ),
  CONSTRAINT chk_application_alias_manual_fields CHECK (
    (evidence_type='AUTO_SAME_SOURCE' AND request_id IS NULL AND command_fingerprint IS NULL AND target_mapping_version_snapshot IS NULL AND changed_by IS NULL AND change_reason IS NULL)
    OR (evidence_type='MANUAL_REBIND' AND request_id IS NOT NULL AND command_fingerprint IS NOT NULL AND target_mapping_version_snapshot IS NOT NULL AND changed_by IS NOT NULL AND change_reason IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='应用 alias 版本化绑定、CAS 与人工重绑幂等事实';

CREATE TABLE t_feedback_primary_problem_fact (
  feedback_source_id BIGINT PRIMARY KEY COMMENT '一对一关联 t_feedback_source.id',
  application_identity_id BIGINT DEFAULT NULL COMMENT '解析成功时关联 t_application_identity.id',
  application_public_id_snapshot BINARY(16) DEFAULT NULL COMMENT '解析时应用 UUID 快照',
  application_display_name_snapshot VARCHAR(200) DEFAULT NULL COMMENT '解析时展示名快照',
  application_mapping_version BIGINT DEFAULT NULL COMMENT '解析时 identity mapping_version',
  application_resolution_status VARCHAR(20) NOT NULL COMMENT 'RESOLVED/UNRESOLVED/AMBIGUOUS',
  classification_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'P3-01 固定 PENDING',
  resolution_rule_version VARCHAR(30) NOT NULL COMMENT '确定性应用身份解析规则版本',
  resolution_reason_code VARCHAR(80) DEFAULT NULL COMMENT '未解析或歧义的稳定原因码',
  resolved_alias_count SMALLINT NOT NULL DEFAULT 0 COMMENT '参与本次解析的规范 alias 数',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_primary_fact_resolution (application_resolution_status,feedback_source_id),
  KEY idx_primary_fact_application (application_identity_id,feedback_source_id),
  CONSTRAINT fk_primary_fact_source FOREIGN KEY (feedback_source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT fk_primary_fact_application FOREIGN KEY (application_identity_id) REFERENCES t_application_identity(id),
  CONSTRAINT chk_primary_fact_resolution CHECK (application_resolution_status IN ('RESOLVED','UNRESOLVED','AMBIGUOUS')),
  CONSTRAINT chk_primary_fact_classification CHECK (classification_status='PENDING'),
  CONSTRAINT chk_primary_fact_alias_count CHECK (resolved_alias_count >= 0 AND resolved_alias_count <= 2),
  CONSTRAINT chk_primary_fact_identity_fields CHECK (
    (application_resolution_status='RESOLVED' AND application_identity_id IS NOT NULL AND application_public_id_snapshot IS NOT NULL AND application_display_name_snapshot IS NOT NULL AND application_mapping_version IS NOT NULL)
    OR (application_resolution_status IN ('UNRESOLVED','AMBIGUOUS') AND application_identity_id IS NULL AND application_public_id_snapshot IS NULL AND application_display_name_snapshot IS NULL AND application_mapping_version IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='反馈主问题影子事实；P3-01 仅建立应用身份解析状态';

-- Dedicated V234 baseline has 70 business tables; P3-01 adds exactly three, so the governed count is now 73.
