-- Reuse the existing authorization table; this migration creates no table.
ALTER TABLE t_auth_data_permission DROP CHECK chk_data_perm_scope;
ALTER TABLE t_auth_data_permission
  ADD CONSTRAINT chk_data_perm_scope CHECK (scope_type IN ('CHANNEL','REGION','MODULE','FIELD'));
