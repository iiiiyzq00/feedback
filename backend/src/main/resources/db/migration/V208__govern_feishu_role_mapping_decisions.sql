CREATE TABLE t_auth_feishu_role_mapping (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_type VARCHAR(20) NOT NULL COMMENT 'USER/USER_GROUP/DEPARTMENT/DEFAULT',
  subject_id VARCHAR(128) NOT NULL COMMENT '飞书稳定ID；DEFAULT固定为*',
  subject_name VARCHAR(100) NOT NULL COMMENT '仅作管理员识别，不参与匹配',
  role_code VARCHAR(30) NOT NULL,
  priority INT NOT NULL DEFAULT 100,
  decision_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/APPROVED/REJECTED/RETIRED',
  description VARCHAR(500) DEFAULT NULL,
  decision_reason VARCHAR(500) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  decided_by BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  decided_at DATETIME(3) DEFAULT NULL,
  CONSTRAINT fk_feishu_mapping_role FOREIGN KEY (role_code) REFERENCES t_auth_role(role_code),
  CONSTRAINT fk_feishu_mapping_creator FOREIGN KEY (created_by) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feishu_mapping_decider FOREIGN KEY (decided_by) REFERENCES t_auth_user(id),
  CONSTRAINT uk_feishu_mapping_subject UNIQUE (rule_type, subject_id),
  INDEX idx_feishu_mapping_decision (decision_status, priority, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='飞书组织事实到平台角色的待决策映射；不直接存储密钥或用户令牌';

