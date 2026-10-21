-- Jira Server returns offset-aware timestamps. API rows before V184 were converted
-- to UTC and then stored in timezone-less DATETIME columns, making Asia/Shanghai
-- business times appear eight hours early. Only current Jira records whose latest
-- authoritative snapshot came from the read-only API are corrected here; file rows
-- and scheduler watermarks are intentionally untouched.
UPDATE t_feedback_source s
JOIN t_import_batch b ON b.id = s.import_batch_id
SET s.source_event_time = DATE_ADD(s.source_event_time, INTERVAL 8 HOUR),
    s.source_update_time = DATE_ADD(s.source_update_time, INTERVAL 8 HOUR),
    s.updated_at = NOW(3)
WHERE s.source_channel = 'JIRA'
  AND b.ingest_method = 'API';

UPDATE t_channel_jira j
JOIN t_feedback_source s ON s.id = j.source_id
JOIN t_import_batch b ON b.id = s.import_batch_id
SET j.jira_created_at = DATE_ADD(j.jira_created_at, INTERVAL 8 HOUR),
    j.jira_updated_at = DATE_ADD(j.jira_updated_at, INTERVAL 8 HOUR),
    j.last_viewed_at = DATE_ADD(j.last_viewed_at, INTERVAL 8 HOUR),
    j.resolved_at = DATE_ADD(j.resolved_at, INTERVAL 8 HOUR)
WHERE s.source_channel = 'JIRA'
  AND b.ingest_method = 'API';
