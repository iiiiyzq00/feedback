UPDATE t_import_template
SET is_active=0,updated_at=CURRENT_TIMESTAMP(3)
WHERE source_channel='JIRA' AND is_active=1;

INSERT INTO t_import_template(
  template_name,source_channel,version,schema_version,header_columns,field_mappings,is_active
)
SELECT 'Jira完整字段导入/API统一映射','JIRA','v1.4.0',schema_version,
       JSON_ARRAY_APPEND(header_columns,'$','评论摘要'),
       JSON_SET(field_mappings,'$.comment_summary','评论摘要'),1
FROM t_import_template old
WHERE old.source_channel='JIRA' AND old.version='v1.3.0'
  AND NOT EXISTS(SELECT 1 FROM t_import_template current
                 WHERE current.source_channel='JIRA' AND current.version='v1.4.0');
