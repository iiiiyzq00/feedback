-- AI classifications that do not require review are governed canonical facts.
-- Preserve source/manual values and never adopt PENDING review results.
UPDATE t_feedback_content c
JOIN t_feedback_ai a ON a.source_id = c.source_id
SET c.issue_category = COALESCE(c.issue_category, a.ai_category),
    c.severity_level = COALESCE(c.severity_level, a.ai_severity),
    c.updated_at = CURRENT_TIMESTAMP(3)
WHERE a.review_status = 'NOT_REQUIRED'
  AND a.ai_category IN ('BUG','HARDWARE','CONTENT_ERROR','DEMAND','CONSULT','PRAISE','COMPLAINT')
  AND a.ai_severity IN ('S0','S1','S2','S3','S4')
  AND (c.issue_category IS NULL OR c.severity_level IS NULL);
