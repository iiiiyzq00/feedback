CREATE TABLE t_feedback_workflow_state (
  feedback_source_id BIGINT PRIMARY KEY,
  processing_status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  distribution_status VARCHAR(30) NOT NULL DEFAULT 'UNASSIGNED',
  source_status_raw VARCHAR(100) DEFAULT NULL,
  source_status_updated_at DATETIME(3) DEFAULT NULL,
  status_mapping_version VARCHAR(40) NOT NULL DEFAULT 'source-status-map-v1',
  status_mapping_result VARCHAR(30) NOT NULL DEFAULT 'DEFAULTED',
  status_mapped_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  processing_status_origin VARCHAR(20) NOT NULL DEFAULT 'SOURCE_MAPPING',
  updated_by BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_feedback_workflow_processing(processing_status,updated_at),
  KEY idx_feedback_workflow_distribution(distribution_status,updated_at),
  CONSTRAINT fk_feedback_workflow_source FOREIGN KEY(feedback_source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT fk_feedback_workflow_actor FOREIGN KEY(updated_by) REFERENCES t_auth_user(id),
  CONSTRAINT chk_feedback_workflow_processing CHECK(processing_status IN
    ('NEW','DISTRIBUTED','PROCESSING','SUSPENDED','RESOLVED','CLOSED')),
  CONSTRAINT chk_feedback_workflow_distribution CHECK(distribution_status IN
    ('UNASSIGNED','NOTIFYING','PENDING_ACCEPTANCE','ACCEPTED','ROLLED_BACK','CANCELLED')),
  CONSTRAINT chk_feedback_workflow_mapping_result CHECK(status_mapping_result IN
    ('MAPPED','DEFAULTED','UNMAPPED','MANUAL_OVERRIDE')),
  CONSTRAINT chk_feedback_workflow_origin CHECK(processing_status_origin IN
    ('SOURCE_MAPPING','PLATFORM_ACTION','MANUAL_OVERRIDE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='统一处理状态、独立分发状态及来源状态映射证据';

INSERT INTO t_feedback_workflow_state(
  feedback_source_id,processing_status,distribution_status,source_status_raw,
  source_status_updated_at,status_mapping_result,processing_status_origin
)
SELECT s.id,
  CASE c.processing_status
    WHEN 'IN_PROGRESS' THEN 'PROCESSING'
    WHEN 'RESOLVED' THEN 'RESOLVED'
    WHEN 'CLOSED' THEN 'CLOSED'
    ELSE CASE WHEN a.feedback_source_id IS NOT NULL THEN 'DISTRIBUTED' ELSE 'NEW' END
  END,
  CASE
    WHEN h.task_status='DELIVERED' THEN 'PENDING_ACCEPTANCE'
    WHEN h.task_status='ACCEPTED' THEN 'ACCEPTED'
    WHEN h.task_status='CANCELLED' THEN 'CANCELLED'
    WHEN a.feedback_source_id IS NOT NULL THEN 'ACCEPTED'
    ELSE 'UNASSIGNED'
  END,
  CASE s.source_channel
    WHEN 'JIRA' THEN j.jira_status_raw
    WHEN 'STATION_FEEDBACK' THEN sf.processing_stage
    ELSE NULL
  END,
  s.source_update_time,
  CASE
    WHEN s.source_channel IN ('SDK','QUALITY','REDBLACK') THEN 'DEFAULTED'
    WHEN s.source_channel='JIRA' AND j.jira_status_raw IS NOT NULL THEN 'MAPPED'
    WHEN s.source_channel='STATION_FEEDBACK' AND sf.processing_stage IS NOT NULL THEN 'MAPPED'
    ELSE 'UNMAPPED'
  END,
  CASE WHEN c.processing_status IN ('IN_PROGRESS','RESOLVED','CLOSED') THEN 'PLATFORM_ACTION' ELSE 'SOURCE_MAPPING' END
FROM t_feedback_source s
JOIN t_feedback_content c ON c.source_id=s.id
LEFT JOIN t_feedback_assignment a ON a.feedback_source_id=s.id
LEFT JOIN (
  SELECT task.feedback_source_id,task.task_status
  FROM t_feedback_handoff_task task
  JOIN (SELECT feedback_source_id,MAX(id) id FROM t_feedback_handoff_task GROUP BY feedback_source_id) latest ON latest.id=task.id
) h ON h.feedback_source_id=s.id
LEFT JOIN t_channel_jira j ON j.source_id=s.id
LEFT JOIN t_channel_station_feedback sf ON sf.source_id=s.id;

ALTER TABLE t_feedback_handoff_task
  ADD COLUMN previous_processing_status VARCHAR(30) DEFAULT NULL AFTER handoff_reason,
  ADD COLUMN previous_distribution_status VARCHAR(30) DEFAULT NULL AFTER previous_processing_status;

ALTER TABLE t_feedback_disposition_event
  ADD COLUMN old_distribution_status VARCHAR(30) DEFAULT NULL AFTER new_status,
  ADD COLUMN new_distribution_status VARCHAR(30) DEFAULT NULL AFTER old_distribution_status;

-- The governed physical business table count is now 54.
