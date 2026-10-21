-- D7-Q: epoch 2 hashes MySQL's persisted JSON representation, making recomputation byte-stable.
ALTER TABLE t_system_audit_log DROP CHECK chk_audit_chain_epoch;
ALTER TABLE t_system_audit_log
  ADD CONSTRAINT chk_audit_chain_epoch CHECK (chain_epoch IN (0,1,2));
