-- Re-evaluate Jira fault times that were parsed from a time-range substring
-- as a two-digit future year (for example 19:59-05-23 -> 2059-05-23).
-- The application backfill runner resolves PENDING rows against jira_created_at.
UPDATE t_feedback_source s
JOIN t_channel_jira j ON j.source_id = s.id
SET s.fault_occurred_at = NULL,
    s.fault_time_precision = NULL,
    s.fault_time_source = NULL,
    s.fault_time_resolution = 'PENDING',
    s.updated_at = NOW(3)
WHERE s.source_channel = 'JIRA'
  AND s.fault_occurred_at > DATE_ADD(COALESCE(j.jira_created_at, s.source_event_time), INTERVAL 1 DAY);
