ALTER TABLE t_import_batch
  ADD COLUMN governance_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER previous_batch_id,
  ADD COLUMN superseded_by_batch_id BIGINT DEFAULT NULL AFTER governance_status,
  ADD COLUMN governance_reason VARCHAR(500) DEFAULT NULL AFTER superseded_by_batch_id,
  ADD COLUMN governed_at DATETIME(3) DEFAULT NULL AFTER governance_reason,
  ADD COLUMN governed_by BIGINT DEFAULT NULL AFTER governed_at,
  ADD CONSTRAINT chk_import_governance_status
    CHECK (governance_status IN ('ACTIVE','SUPERSEDED','NO_CHANGE')),
  ADD CONSTRAINT fk_import_superseded_by
    FOREIGN KEY (superseded_by_batch_id) REFERENCES t_import_batch(id),
  ADD KEY idx_import_governance_created (governance_status, created_at),
  ADD KEY idx_import_superseded_by (superseded_by_batch_id);
