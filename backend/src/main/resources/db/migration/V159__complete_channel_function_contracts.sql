ALTER TABLE t_feedback_content ADD COLUMN feedback_tags JSON DEFAULT NULL AFTER problem_type;
ALTER TABLE t_channel_jira ADD COLUMN comment_summary TEXT DEFAULT NULL AFTER description_text;
ALTER TABLE t_channel_redblack
  ADD COLUMN evaluation_object VARCHAR(200) DEFAULT NULL,
  ADD COLUMN evaluation_dimension VARCHAR(100) DEFAULT NULL,
  ADD COLUMN impact_level_raw VARCHAR(50) DEFAULT NULL,
  ADD COLUMN data_period VARCHAR(100) DEFAULT NULL,
  ADD COLUMN source_department_masked VARCHAR(100) DEFAULT NULL,
  ADD COLUMN source_role_masked VARCHAR(100) DEFAULT NULL;
UPDATE t_channel_redblack SET uid_raw=NULL WHERE uid_raw IS NOT NULL;
ALTER TABLE t_import_batch
  ADD COLUMN inserted_rows INT NOT NULL DEFAULT 0 AFTER success_rows,
  ADD COLUMN updated_rows INT NOT NULL DEFAULT 0 AFTER inserted_rows;
