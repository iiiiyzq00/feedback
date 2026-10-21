CREATE TABLE t_feedback_assignment (
  feedback_source_id BIGINT PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  assignment_version BIGINT NOT NULL DEFAULT 1,
  assigned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  assigned_by BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_feedback_assignment_owner (owner_user_id,updated_at),
  CONSTRAINT fk_feedback_assignment_source FOREIGN KEY(feedback_source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT fk_feedback_assignment_owner FOREIGN KEY(owner_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_assignment_actor FOREIGN KEY(assigned_by) REFERENCES t_auth_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='反馈当前负责人（平台处置事实）';

CREATE TABLE t_feedback_handoff_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id VARCHAR(100) NOT NULL,
  feedback_source_id BIGINT NOT NULL,
  from_owner_user_id BIGINT DEFAULT NULL,
  to_owner_user_id BIGINT NOT NULL,
  initiated_by BIGINT NOT NULL,
  handoff_reason VARCHAR(500) NOT NULL,
  task_status VARCHAR(20) NOT NULL DEFAULT 'DELIVERED',
  notification_id BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  accepted_at DATETIME(3) DEFAULT NULL,
  UNIQUE KEY uk_feedback_handoff_request (request_id),
  KEY idx_feedback_handoff_target (to_owner_user_id,task_status,created_at),
  KEY idx_feedback_handoff_source (feedback_source_id,created_at),
  CONSTRAINT fk_feedback_handoff_source FOREIGN KEY(feedback_source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT fk_feedback_handoff_from_owner FOREIGN KEY(from_owner_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_handoff_to_owner FOREIGN KEY(to_owner_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_handoff_actor FOREIGN KEY(initiated_by) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_handoff_notification FOREIGN KEY(notification_id) REFERENCES t_alert_notification(id),
  CONSTRAINT chk_feedback_handoff_status CHECK(task_status IN ('DELIVERED','ACCEPTED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='反馈任务交接及通知凭证';

CREATE TABLE t_feedback_disposition_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  feedback_source_id BIGINT NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  handler_user_id BIGINT NOT NULL,
  previous_owner_user_id BIGINT DEFAULT NULL,
  new_owner_user_id BIGINT DEFAULT NULL,
  old_status VARCHAR(20) DEFAULT NULL,
  new_status VARCHAR(20) DEFAULT NULL,
  handoff_task_id BIGINT DEFAULT NULL,
  event_reason VARCHAR(500) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_feedback_disposition_source (feedback_source_id,created_at,id),
  KEY idx_feedback_disposition_handler (handler_user_id,created_at),
  CONSTRAINT fk_feedback_disposition_source FOREIGN KEY(feedback_source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT fk_feedback_disposition_handler FOREIGN KEY(handler_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_disposition_previous_owner FOREIGN KEY(previous_owner_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_disposition_new_owner FOREIGN KEY(new_owner_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_disposition_handoff FOREIGN KEY(handoff_task_id) REFERENCES t_feedback_handoff_task(id),
  CONSTRAINT chk_feedback_disposition_type CHECK(event_type IN ('CLAIMED','HANDOFF','PROCESSED','STATUS_CHANGED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='反馈不可覆盖的经办人记录';
