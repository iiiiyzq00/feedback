-- Parse relative Jira values such as "18号晚上5点到7点左右" against the
-- authoritative Jira creation timestamp. Existing source text remains unchanged.
UPDATE t_feedback_source
SET fault_occurred_at=NULL,fault_time_precision=NULL,
    fault_time_source=NULL,fault_time_resolution='PENDING',updated_at=NOW(3)
WHERE source_channel='JIRA' AND fault_time_resolution IN ('UNPARSEABLE','MISSING');
