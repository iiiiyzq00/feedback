ALTER TABLE t_auth_sso_state
  ADD COLUMN redirect_uri VARCHAR(500) DEFAULT NULL AFTER return_path;

