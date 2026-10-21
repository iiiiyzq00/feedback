ALTER TABLE t_feedback_disposition_event
  DROP CHECK chk_feedback_disposition_type,
  ADD CONSTRAINT chk_feedback_disposition_type CHECK(event_type IN (
    'CLAIMED','HANDOFF','HANDOFF_ACCEPTED','HANDOFF_CANCELLED','HANDOFF_ROLLED_BACK','PROCESSED','STATUS_CHANGED',
    'REOPENED','JIRA_OWNER_INITIALIZED'
  ));

-- Jira assignee initialization reuses the existing assignment and workflow facts.
-- No new physical business table is introduced.
