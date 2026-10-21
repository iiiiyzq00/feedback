-- Re-evaluate Jira fault-time fields that contain only a governed clock time.
-- The resolver assigns the nearest occurrence that is not later than Jira creation time.
UPDATE t_feedback_source
SET fault_occurred_at = NULL,
    fault_time_precision = NULL,
    fault_time_resolution = 'PENDING',
    updated_at = NOW(3)
WHERE source_channel = 'JIRA'
  AND fault_time_resolution = 'UNPARSEABLE'
  AND fault_time_raw REGEXP '^[[:space:]]*([01]?[0-9]|2[0-3])[:：][0-5]?[0-9](:[0-5]?[0-9])?[[:space:]]*(左右|前后|许)?[[:space:]]*$';
