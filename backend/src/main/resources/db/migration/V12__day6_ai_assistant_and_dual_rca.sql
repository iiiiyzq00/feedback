ALTER TABLE t_root_cause_analysis
  ADD COLUMN rca_type VARCHAR(10) NOT NULL DEFAULT 'DEEP' AFTER trigger_type,
  ADD COLUMN facts_snapshot JSON DEFAULT NULL AFTER rca_type,
  ADD COLUMN orchestration_version VARCHAR(20) DEFAULT NULL AFTER facts_snapshot;

ALTER TABLE t_root_cause_analysis
  ADD CONSTRAINT chk_rca_type CHECK (rca_type IN ('QUICK','DEEP'));

ALTER TABLE t_root_cause_analysis
  ADD KEY idx_incident_rca_type (incident_id, rca_type, created_at);
