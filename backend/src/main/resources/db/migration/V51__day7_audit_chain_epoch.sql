-- D7-Q: isolate the pre-acceptance chain whose lock was released before commit.
-- Existing rows remain immutable evidence in epoch 0; only corrected writes enter epoch 1.
ALTER TABLE t_system_audit_log
  ADD COLUMN chain_epoch INT NOT NULL DEFAULT 0 AFTER detail_schema_version,
  ADD KEY idx_audit_chain_epoch (chain_epoch,id,event_hash),
  ADD CONSTRAINT chk_audit_chain_epoch CHECK (chain_epoch IN (0,1));
