-- V203's first governed parser exposed a partial-date edge case for Chinese hour text.
-- Reset only Jira derived facts; the idempotent application backfill recomputes them with v2.
UPDATE t_feedback_source
SET fault_occurred_at=NULL,fault_time_raw=NULL,fault_time_precision=NULL,
    fault_time_source=NULL,fault_time_resolution='PENDING',updated_at=NOW(3)
WHERE source_channel='JIRA';
