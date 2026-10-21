ALTER TABLE t_feedback_ai
  ADD COLUMN review_comment VARCHAR(1000) DEFAULT NULL AFTER reviewed_at;
