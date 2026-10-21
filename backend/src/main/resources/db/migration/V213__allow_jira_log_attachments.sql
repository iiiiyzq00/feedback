ALTER TABLE t_feedback_attachment DROP CHECK chk_attachment_source_kind;
ALTER TABLE t_feedback_attachment
  ADD CONSTRAINT chk_attachment_source_kind
  CHECK (source_kind IN ('SDK','IMPORT','STATION_FEEDBACK','JIRA'));
