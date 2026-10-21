ALTER TABLE t_auth_feishu_role_mapping
  ADD COLUMN grant_kind VARCHAR(20) NOT NULL DEFAULT 'ROLE' AFTER subject_name,
  ADD COLUMN scope_type VARCHAR(20) DEFAULT NULL AFTER role_code,
  ADD COLUMN scope_value VARCHAR(100) DEFAULT NULL AFTER scope_type;

ALTER TABLE t_auth_data_permission
  ADD COLUMN grant_source VARCHAR(30) DEFAULT NULL AFTER effect,
  ADD COLUMN source_ref VARCHAR(100) DEFAULT NULL AFTER grant_source;

CREATE TABLE t_auth_feishu_user_org_fact (
  user_id BIGINT PRIMARY KEY,
  group_ids JSON NOT NULL,
  department_ids JSON NOT NULL,
  employment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  synced_at DATETIME(3) NOT NULL,
  last_error_code VARCHAR(100) DEFAULT NULL,
  CONSTRAINT fk_feishu_org_fact_user FOREIGN KEY (user_id) REFERENCES t_auth_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='飞书组织事实缓存；仅保存稳定ID，不保存组织通讯录正文';

CREATE TABLE t_auth_feishu_sync_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_status VARCHAR(20) NOT NULL,
  trigger_type VARCHAR(20) NOT NULL,
  requested_by BIGINT DEFAULT NULL,
  total_users INT NOT NULL DEFAULT 0,
  changed_users INT NOT NULL DEFAULT 0,
  disabled_users INT NOT NULL DEFAULT 0,
  failed_users INT NOT NULL DEFAULT 0,
  error_code VARCHAR(100) DEFAULT NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) DEFAULT NULL,
  CONSTRAINT fk_feishu_sync_requester FOREIGN KEY (requested_by) REFERENCES t_auth_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='飞书组织自动授权同步运行记录';
