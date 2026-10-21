ALTER TABLE t_auth_user
  ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 COMMENT 'JWT session generation; increment to revoke issued tokens' AFTER password_hash;

