-- 分发后的人工提醒/催办事实；提醒不得改变负责人、处理状态或分发状态。
ALTER TABLE t_feedback_disposition_event
  DROP CHECK chk_feedback_disposition_type,
  ADD CONSTRAINT chk_feedback_disposition_type CHECK(event_type IN (
    'CLAIMED','HANDOFF','HANDOFF_ACCEPTED','HANDOFF_CANCELLED','HANDOFF_ROLLED_BACK',
    'PROCESSED','STATUS_CHANGED','REOPENED','JIRA_OWNER_INITIALIZED','REMINDER_SENT'
  ));

CREATE TABLE t_feedback_reminder (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id VARCHAR(100) NOT NULL,
  feedback_source_id BIGINT NOT NULL,
  handoff_task_id BIGINT DEFAULT NULL,
  reminded_by BIGINT NOT NULL,
  target_user_id BIGINT NOT NULL,
  reminder_type VARCHAR(20) NOT NULL,
  reason_code VARCHAR(40) NOT NULL,
  reminder_message VARCHAR(500) NOT NULL,
  processing_status_snapshot VARCHAR(20) NOT NULL,
  distribution_status_snapshot VARCHAR(20) NOT NULL,
  owner_user_id_snapshot BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_feedback_reminder_request (request_id),
  KEY idx_feedback_reminder_source (feedback_source_id,created_at,id),
  KEY idx_feedback_reminder_target (target_user_id,created_at,id),
  CONSTRAINT fk_feedback_reminder_source FOREIGN KEY(feedback_source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT fk_feedback_reminder_handoff FOREIGN KEY(handoff_task_id) REFERENCES t_feedback_handoff_task(id),
  CONSTRAINT fk_feedback_reminder_actor FOREIGN KEY(reminded_by) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_reminder_target FOREIGN KEY(target_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_reminder_owner_snapshot FOREIGN KEY(owner_user_id_snapshot) REFERENCES t_auth_user(id),
  CONSTRAINT chk_feedback_reminder_type CHECK(reminder_type IN ('ACCEPTANCE','PROGRESS')),
  CONSTRAINT chk_feedback_reminder_reason CHECK(reason_code IN (
    'ACCEPT_TASK','UPDATE_PROGRESS','EXPECTED_TIME_EXCEEDED','BUSINESS_IMPACT_EXPANDED','OTHER'
  ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='反馈人工提醒催办及站内通知事实';

-- The governed physical business table count is now 56.
