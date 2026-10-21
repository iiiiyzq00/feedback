-- Forward-only expansion for the SDK attachment type already accepted by the API.
ALTER TABLE t_feedback_attachment DROP CHECK chk_attachment_type;
ALTER TABLE t_feedback_attachment
  ADD CONSTRAINT chk_attachment_type
  CHECK (attachment_type IN ('SCREENSHOT','ANNOTATED_SCREENSHOT','SCREEN_RECORDING','LOG','IMAGE','VIDEO'));
