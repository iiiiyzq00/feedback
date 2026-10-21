ALTER TABLE t_feedback_attachment
  ADD COLUMN source_attachment_id VARCHAR(100) DEFAULT NULL AFTER client_attachment_id,
  ADD UNIQUE KEY uk_attachment_source_identity(source_kind, source_attachment_id);
