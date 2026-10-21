-- Forward-only Feishu SSO identity, state and one-time login-code facts.
CREATE TABLE t_auth_external_identity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  provider VARCHAR(30) NOT NULL,
  provider_subject VARCHAR(100) NOT NULL,
  union_id VARCHAR(100) DEFAULT NULL,
  employee_id VARCHAR(100) DEFAULT NULL,
  tenant_key VARCHAR(100) DEFAULT NULL,
  last_login_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_external_provider_subject(provider,provider_subject),
  UNIQUE KEY uk_external_provider_union(provider,union_id),
  KEY idx_external_user(user_id),
  CONSTRAINT fk_external_identity_user FOREIGN KEY(user_id) REFERENCES t_auth_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='外部身份与平台影子帐号绑定';

CREATE TABLE t_auth_sso_state (
  state_hash BINARY(32) PRIMARY KEY,
  provider VARCHAR(30) NOT NULL,
  return_path VARCHAR(500) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  consumed_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_sso_state_expiry(expires_at,consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='OAuth state单次校验事实';

CREATE TABLE t_auth_sso_login_code (
  code_hash BINARY(32) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  return_path VARCHAR(500) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  consumed_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_sso_code_expiry(expires_at,consumed_at),
  CONSTRAINT fk_sso_login_code_user FOREIGN KEY(user_id) REFERENCES t_auth_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='OAuth回调到前端的短时单次登录码';
