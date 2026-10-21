ALTER TABLE t_feedback_ai
  ADD COLUMN classification_taxonomy_version VARCHAR(30) NULL AFTER classification_model_version;

UPDATE t_feedback_ai
SET classification_taxonomy_version='ai-taxonomy-v1'
WHERE classification_taxonomy_version IS NULL;

ALTER TABLE t_feedback_ai
  MODIFY classification_taxonomy_version VARCHAR(30) NOT NULL DEFAULT 'ai-taxonomy-v1';
