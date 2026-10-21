UPDATE t_feedback_workflow_state w
JOIN t_feedback_source s ON s.id=w.feedback_source_id
LEFT JOIN t_channel_jira j ON j.source_id=s.id
LEFT JOIN t_channel_station_feedback sf ON sf.source_id=s.id
SET w.processing_status=CASE
      WHEN s.source_channel='JIRA' AND LOWER(COALESCE(j.jira_status_raw,'')) IN ('closed','关闭','已关闭') THEN 'CLOSED'
      WHEN s.source_channel='JIRA' AND (j.resolution IS NOT NULL OR LOWER(COALESCE(j.jira_status_raw,'')) IN ('resolved','done','解决','已解决','完成','已完成')) THEN 'RESOLVED'
      WHEN s.source_channel='STATION_FEEDBACK' AND sf.processing_stage='COMPLETED' THEN 'RESOLVED'
      ELSE w.processing_status
    END,
    w.status_mapped_at=NOW(3)
WHERE w.processing_status_origin='SOURCE_MAPPING';

CREATE TRIGGER trg_feedback_content_workflow_default
AFTER INSERT ON t_feedback_content
FOR EACH ROW
INSERT IGNORE INTO t_feedback_workflow_state(
  feedback_source_id,processing_status,distribution_status,status_mapping_result,processing_status_origin
) VALUES(
  NEW.source_id,
  CASE NEW.processing_status WHEN 'IN_PROGRESS' THEN 'PROCESSING' WHEN 'RESOLVED' THEN 'RESOLVED' WHEN 'CLOSED' THEN 'CLOSED' ELSE 'NEW' END,
  'UNASSIGNED','DEFAULTED',
  CASE WHEN NEW.processing_status IN ('IN_PROGRESS','RESOLVED','CLOSED') THEN 'PLATFORM_ACTION' ELSE 'SOURCE_MAPPING' END
);

CREATE TRIGGER trg_channel_jira_workflow_mapping
AFTER INSERT ON t_channel_jira
FOR EACH ROW
INSERT INTO t_feedback_workflow_state(
  feedback_source_id,processing_status,distribution_status,source_status_raw,status_mapping_result,processing_status_origin
) VALUES(
  NEW.source_id,
  CASE
    WHEN LOWER(COALESCE(NEW.jira_status_raw,'')) IN ('closed','关闭','已关闭') THEN 'CLOSED'
    WHEN NEW.resolution IS NOT NULL OR LOWER(COALESCE(NEW.jira_status_raw,'')) IN ('resolved','done','解决','已解决','完成','已完成') THEN 'RESOLVED'
    ELSE 'NEW'
  END,
  'UNASSIGNED',NEW.jira_status_raw,CASE WHEN NEW.jira_status_raw IS NULL THEN 'UNMAPPED' ELSE 'MAPPED' END,'SOURCE_MAPPING'
)
ON DUPLICATE KEY UPDATE
  source_status_raw=VALUES(source_status_raw),source_status_updated_at=NOW(3),status_mapping_result=VALUES(status_mapping_result),status_mapped_at=NOW(3),
  processing_status=IF(processing_status_origin='SOURCE_MAPPING',VALUES(processing_status),processing_status);

CREATE TRIGGER trg_channel_station_workflow_mapping
AFTER INSERT ON t_channel_station_feedback
FOR EACH ROW
INSERT INTO t_feedback_workflow_state(
  feedback_source_id,processing_status,distribution_status,source_status_raw,status_mapping_result,processing_status_origin
) VALUES(
  NEW.source_id,CASE WHEN NEW.processing_stage='COMPLETED' THEN 'RESOLVED' ELSE 'NEW' END,
  'UNASSIGNED',NEW.processing_stage,'MAPPED','SOURCE_MAPPING'
)
ON DUPLICATE KEY UPDATE
  source_status_raw=VALUES(source_status_raw),source_status_updated_at=NOW(3),status_mapping_result='MAPPED',status_mapped_at=NOW(3),
  processing_status=IF(processing_status_origin='SOURCE_MAPPING',VALUES(processing_status),processing_status);

CREATE TRIGGER trg_channel_jira_workflow_mapping_update
AFTER UPDATE ON t_channel_jira
FOR EACH ROW
UPDATE t_feedback_workflow_state
SET source_status_raw=NEW.jira_status_raw,source_status_updated_at=NOW(3),
    status_mapping_result=CASE WHEN NEW.jira_status_raw IS NULL THEN 'UNMAPPED' ELSE 'MAPPED' END,status_mapped_at=NOW(3),
    processing_status=IF(processing_status_origin='SOURCE_MAPPING',CASE
      WHEN LOWER(COALESCE(NEW.jira_status_raw,'')) IN ('closed','关闭','已关闭') THEN 'CLOSED'
      WHEN NEW.resolution IS NOT NULL OR LOWER(COALESCE(NEW.jira_status_raw,'')) IN ('resolved','done','解决','已解决','完成','已完成') THEN 'RESOLVED'
      ELSE 'NEW' END,processing_status)
WHERE feedback_source_id=NEW.source_id;

CREATE TRIGGER trg_channel_station_workflow_mapping_update
AFTER UPDATE ON t_channel_station_feedback
FOR EACH ROW
UPDATE t_feedback_workflow_state
SET source_status_raw=NEW.processing_stage,source_status_updated_at=NOW(3),status_mapping_result='MAPPED',status_mapped_at=NOW(3),
    processing_status=IF(processing_status_origin='SOURCE_MAPPING',CASE WHEN NEW.processing_stage='COMPLETED' THEN 'RESOLVED' ELSE 'NEW' END,processing_status)
WHERE feedback_source_id=NEW.source_id;
