ALTER TABLE t_ai_report
  ADD COLUMN data_latest_time DATETIME(3) DEFAULT NULL AFTER report_period_end,
  ADD COLUMN raw_record_count BIGINT NOT NULL DEFAULT 0 AFTER data_latest_time,
  ADD COLUMN unique_problem_count BIGINT NOT NULL DEFAULT 0 AFTER raw_record_count,
  ADD COLUMN generated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER report_status,
  ADD COLUMN generation_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL' AFTER generated_at;

ALTER TABLE t_root_cause_analysis
  ADD COLUMN trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL' AFTER incident_id,
  ADD COLUMN correlation_result JSON DEFAULT NULL AFTER root_cause,
  ADD COLUMN five_whys_result JSON DEFAULT NULL AFTER correlation_result,
  ADD COLUMN impact_result JSON DEFAULT NULL AFTER five_whys_result,
  ADD COLUMN confidence DECIMAL(7,6) DEFAULT NULL AFTER impact_result,
  ADD COLUMN review_comment VARCHAR(1000) DEFAULT NULL AFTER solution;

ALTER TABLE t_root_cause_analysis ADD CONSTRAINT chk_rca_trigger CHECK (trigger_type IN ('INCIDENT','MANUAL'));
ALTER TABLE t_ai_report DROP CONSTRAINT chk_report_type;
ALTER TABLE t_ai_report ADD CONSTRAINT chk_report_type CHECK (report_type IN ('DAILY','WEEKLY','VERSION','CORRELATION','REDBLACK','RISK'));
ALTER TABLE t_ai_report ADD CONSTRAINT chk_report_source CHECK (generation_source IN ('MANUAL','SCHEDULED'));
