DROP TRIGGER IF EXISTS trg_channel_jira_workflow_mapping;
DROP TRIGGER IF EXISTS trg_channel_jira_workflow_mapping_update;

UPDATE t_feedback_workflow_state w
JOIN t_channel_jira j ON j.source_id=w.feedback_source_id
SET w.source_status_raw=j.jira_status_raw,
    w.source_status_updated_at=NOW(3),
    w.status_mapping_version='jira-processing-status-v2',
    w.status_mapping_result=CASE WHEN j.jira_status_raw IS NULL AND j.resolution IS NULL THEN 'UNMAPPED' ELSE 'MAPPED' END,
    w.status_mapped_at=NOW(3),
    w.processing_status=CASE
      WHEN COALESCE(j.jira_status_raw,'') REGEXP '^关闭' THEN 'CLOSED'
      WHEN COALESCE(j.jira_status_raw,'') REGEXP '挂起|等待第三方' OR COALESCE(j.resolution,'')='已挂起' THEN 'SUSPENDED'
      WHEN COALESCE(j.jira_status_raw,'') REGEXP '^(已解决|完成)$' THEN 'RESOLVED'
      WHEN COALESCE(j.jira_status_raw,'') REGEXP '处理中|测试中|开发中|验收|送测' THEN 'PROCESSING'
      WHEN COALESCE(j.resolution,'') IN ('已解决','已修复','已完成') THEN 'RESOLVED'
      ELSE 'NEW'
    END
WHERE w.processing_status_origin='SOURCE_MAPPING';

CREATE TRIGGER trg_channel_jira_workflow_mapping
AFTER INSERT ON t_channel_jira
FOR EACH ROW
INSERT INTO t_feedback_workflow_state(
  feedback_source_id,processing_status,distribution_status,source_status_raw,status_mapping_version,
  status_mapping_result,processing_status_origin
) VALUES(
  NEW.source_id,
  CASE
    WHEN COALESCE(NEW.jira_status_raw,'') REGEXP '^关闭' THEN 'CLOSED'
    WHEN COALESCE(NEW.jira_status_raw,'') REGEXP '挂起|等待第三方' OR COALESCE(NEW.resolution,'')='已挂起' THEN 'SUSPENDED'
    WHEN COALESCE(NEW.jira_status_raw,'') REGEXP '^(已解决|完成)$' THEN 'RESOLVED'
    WHEN COALESCE(NEW.jira_status_raw,'') REGEXP '处理中|测试中|开发中|验收|送测' THEN 'PROCESSING'
    WHEN COALESCE(NEW.resolution,'') IN ('已解决','已修复','已完成') THEN 'RESOLVED'
    ELSE 'NEW'
  END,
  'UNASSIGNED',NEW.jira_status_raw,'jira-processing-status-v2',
  CASE WHEN NEW.jira_status_raw IS NULL AND NEW.resolution IS NULL THEN 'UNMAPPED' ELSE 'MAPPED' END,'SOURCE_MAPPING'
)
ON DUPLICATE KEY UPDATE
  source_status_raw=VALUES(source_status_raw),source_status_updated_at=NOW(3),status_mapping_version=VALUES(status_mapping_version),
  status_mapping_result=VALUES(status_mapping_result),status_mapped_at=NOW(3),
  processing_status=IF(processing_status_origin='SOURCE_MAPPING',VALUES(processing_status),processing_status);

CREATE TRIGGER trg_channel_jira_workflow_mapping_update
AFTER UPDATE ON t_channel_jira
FOR EACH ROW
UPDATE t_feedback_workflow_state
SET source_status_raw=NEW.jira_status_raw,source_status_updated_at=NOW(3),status_mapping_version='jira-processing-status-v2',
    status_mapping_result=CASE WHEN NEW.jira_status_raw IS NULL AND NEW.resolution IS NULL THEN 'UNMAPPED' ELSE 'MAPPED' END,
    status_mapped_at=NOW(3),
    processing_status=IF(processing_status_origin='SOURCE_MAPPING',CASE
      WHEN COALESCE(NEW.jira_status_raw,'') REGEXP '^关闭' THEN 'CLOSED'
      WHEN COALESCE(NEW.jira_status_raw,'') REGEXP '挂起|等待第三方' OR COALESCE(NEW.resolution,'')='已挂起' THEN 'SUSPENDED'
      WHEN COALESCE(NEW.jira_status_raw,'') REGEXP '^(已解决|完成)$' THEN 'RESOLVED'
      WHEN COALESCE(NEW.jira_status_raw,'') REGEXP '处理中|测试中|开发中|验收|送测' THEN 'PROCESSING'
      WHEN COALESCE(NEW.resolution,'') IN ('已解决','已修复','已完成') THEN 'RESOLVED'
      ELSE 'NEW' END,processing_status)
WHERE feedback_source_id=NEW.source_id;
