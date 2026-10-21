UPDATE t_feedback_source
SET source_update_time = COALESCE(source_event_time, ingest_time)
WHERE source_update_time IS NULL;

ALTER TABLE t_feedback_source
  MODIFY source_update_time DATETIME(3) NOT NULL;
