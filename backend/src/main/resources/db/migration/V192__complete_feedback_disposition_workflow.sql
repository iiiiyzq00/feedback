ALTER TABLE t_feedback_disposition_event
  DROP CHECK chk_feedback_disposition_type,
  MODIFY COLUMN event_type VARCHAR(30) NOT NULL,
  MODIFY COLUMN event_reason VARCHAR(2000) DEFAULT NULL,
  ADD CONSTRAINT chk_feedback_disposition_type CHECK(event_type IN (
    'CLAIMED','HANDOFF','HANDOFF_ACCEPTED','HANDOFF_CANCELLED','PROCESSED','STATUS_CHANGED'
  ));

-- The governed physical business table count remains 48.
