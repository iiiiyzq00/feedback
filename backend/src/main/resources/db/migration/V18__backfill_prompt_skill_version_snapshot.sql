-- Forward-only follow-up to V16: preserve the published skill version used by
-- existing prompt definitions. V16 is immutable after being applied.
UPDATE t_ai_task p
JOIN (
  SELECT skill_code, skill_version
  FROM (
    SELECT skill_code,
           skill_version,
           ROW_NUMBER() OVER (PARTITION BY skill_code ORDER BY id DESC) AS rn
    FROM t_ai_task
    WHERE record_type = 'SKILL_DEF'
      AND lifecycle_status = 'PUBLISHED'
  ) ranked
  WHERE rn = 1
) s ON s.skill_code = p.skill_code
SET p.skill_version_snapshot = s.skill_version
WHERE p.record_type = 'PROMPT_VERSION'
  AND p.skill_version_snapshot IS NULL;
