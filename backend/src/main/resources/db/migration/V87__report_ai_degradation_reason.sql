ALTER TABLE t_ai_report
    ADD COLUMN degradation_reason VARCHAR(120) NULL AFTER degraded;

UPDATE t_ai_report
SET degradation_reason='REPORT_PROMPT_UNAVAILABLE'
WHERE degraded=TRUE AND model_name_snapshot IS NULL AND degradation_reason IS NULL;
