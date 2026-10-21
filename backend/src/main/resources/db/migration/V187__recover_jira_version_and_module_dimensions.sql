UPDATE t_feedback_source s
JOIN t_feedback_content c ON c.source_id=s.id
SET s.app_version=UPPER(REGEXP_SUBSTR(
  SUBSTRING(c.description,LOCATE('【系统版本',c.description),300),
  'V[0-9]+(\\.[0-9]+){2,3}',1,1,'i'
))
WHERE s.source_channel='JIRA'
  AND NULLIF(TRIM(s.app_version),'') IS NULL
  AND c.is_deleted=0
  AND LOCATE('【系统版本',c.description)>0
  AND REGEXP_SUBSTR(
    SUBSTRING(c.description,LOCATE('【系统版本',c.description),300),
    'V[0-9]+(\\.[0-9]+){2,3}',1,1,'i'
  ) IS NOT NULL;

UPDATE t_feedback_content c
JOIN t_feedback_source s ON s.id=c.source_id
JOIN t_channel_jira j ON j.source_id=s.id
SET c.function_module=LEFT(TRIM(j.module_raw),100)
WHERE s.source_channel='JIRA'
  AND c.is_deleted=0
  AND NULLIF(TRIM(c.function_module),'') IS NULL
  AND NULLIF(TRIM(j.module_raw),'') IS NOT NULL;
