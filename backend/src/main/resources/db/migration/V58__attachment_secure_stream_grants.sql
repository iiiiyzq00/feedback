CREATE TABLE t_attachment_access_grant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  grant_hash BINARY(32) NOT NULL,
  attachment_id BIGINT NOT NULL,
  source_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reason VARCHAR(200) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  consumed_at DATETIME(3) DEFAULT NULL,
  revoked_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_attachment_grant_hash (grant_hash),
  KEY idx_attachment_grant_expiry (expires_at,consumed_at,revoked_at),
  CONSTRAINT fk_attachment_grant_attachment FOREIGN KEY (attachment_id) REFERENCES t_feedback_attachment(id),
  CONSTRAINT fk_attachment_grant_source FOREIGN KEY (source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT fk_attachment_grant_user FOREIGN KEY (user_id) REFERENCES t_auth_user(id),
  CONSTRAINT chk_attachment_grant_reason CHECK (CHAR_LENGTH(reason) BETWEEN 3 AND 200)
) ENGINE=InnoDB COMMENT='一次性、短时、绑定用户的附件安全流授权；只保存Token哈希';

