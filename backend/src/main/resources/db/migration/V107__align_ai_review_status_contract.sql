-- Align the database constraint with the reviewed AI status contract used by
-- the service and the v6 product specification. Preserve legacy rows before
-- tightening the constraint to the current vocabulary.
UPDATE t_feedback_ai SET review_status='CONFIRMED' WHERE review_status='REVIEWED';
UPDATE t_feedback_ai SET review_status='CORRECTED' WHERE review_status='OVERRULED';

ALTER TABLE t_feedback_ai
  DROP CHECK chk_ai_review_status,
  ADD CONSTRAINT chk_ai_review_status CHECK (
    review_status IN ('NOT_REQUIRED','PENDING','CONFIRMED','CORRECTED','SKIPPED')
  );
