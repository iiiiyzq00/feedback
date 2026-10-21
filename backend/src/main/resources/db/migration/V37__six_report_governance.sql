ALTER TABLE t_ai_report DROP CHECK chk_report_type;
ALTER TABLE t_ai_report MODIFY report_type VARCHAR(30) NOT NULL;
UPDATE t_ai_report SET report_type=CASE report_type WHEN 'DAILY' THEN 'DAILY_INGEST' WHEN 'WEEKLY' THEN 'WEEKLY_TREND' WHEN 'VERSION' THEN 'VERSION_RELEASE' WHEN 'CORRELATION' THEN 'QUALITY_LINKAGE' WHEN 'REDBLACK' THEN 'REDBLACK_ANALYSIS' WHEN 'RISK' THEN 'RISK_BRIEF' ELSE report_type END;
ALTER TABLE t_ai_report
  ADD COLUMN facts_snapshot JSON DEFAULT NULL AFTER report_content,
  ADD COLUMN generated_summary TEXT DEFAULT NULL AFTER facts_snapshot,
  ADD COLUMN validation_status VARCHAR(10) NOT NULL DEFAULT 'PASS' AFTER generated_summary,
  ADD COLUMN validation_errors JSON DEFAULT NULL AFTER validation_status,
  ADD COLUMN prompt_version_snapshot VARCHAR(20) DEFAULT NULL AFTER validation_errors,
  ADD COLUMN skill_version_snapshot VARCHAR(20) DEFAULT NULL AFTER prompt_version_snapshot,
  ADD COLUMN model_name_snapshot VARCHAR(100) DEFAULT NULL AFTER skill_version_snapshot,
  ADD COLUMN degraded BOOLEAN NOT NULL DEFAULT FALSE AFTER model_name_snapshot,
  ADD COLUMN scope_snapshot JSON DEFAULT NULL AFTER degraded;
ALTER TABLE t_ai_report ADD CONSTRAINT chk_report_type CHECK (report_type IN ('DAILY_INGEST','WEEKLY_TREND','VERSION_RELEASE','QUALITY_LINKAGE','REDBLACK_ANALYSIS','RISK_BRIEF'));
ALTER TABLE t_ai_report ADD CONSTRAINT chk_report_validation CHECK (validation_status IN ('PASS','PARTIAL','FAILED'));
ALTER TABLE t_ai_report ADD CONSTRAINT chk_report_validation_errors CHECK ((validation_status='PASS' AND (validation_errors IS NULL OR JSON_LENGTH(validation_errors)=0)) OR validation_status<>'PASS');
