ALTER TABLE t_product_feedback_request
  DROP CHECK chk_product_feedback_status,
  ADD COLUMN priority_code VARCHAR(10) NOT NULL DEFAULT 'P2' AFTER request_status,
  ADD COLUMN planned_version VARCHAR(80) DEFAULT NULL AFTER priority_code,
  ADD COLUMN resolution_note VARCHAR(1000) DEFAULT NULL AFTER planned_version,
  ADD COLUMN accepted_at DATETIME(3) DEFAULT NULL AFTER handler_user_id,
  ADD COLUMN completed_at DATETIME(3) DEFAULT NULL AFTER accepted_at,
  ADD CONSTRAINT chk_product_feedback_priority CHECK(priority_code IN ('P0','P1','P2','P3'));

UPDATE t_product_feedback_request SET request_status='COMPLETED' WHERE request_status IN ('RESOLVED','CLOSED');

ALTER TABLE t_product_feedback_request
  ADD CONSTRAINT chk_product_feedback_status CHECK(request_status IN
    ('OPEN','ACCEPTED','IN_PROGRESS','PENDING_ACCEPTANCE','COMPLETED','REJECTED','DEFERRED'));

CREATE TABLE t_product_feedback_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id BIGINT NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  previous_handler_user_id BIGINT DEFAULT NULL,
  new_handler_user_id BIGINT DEFAULT NULL,
  old_status VARCHAR(30) DEFAULT NULL,
  new_status VARCHAR(30) DEFAULT NULL,
  event_reason VARCHAR(1000) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_product_feedback_event_request(request_id,created_at,id),
  KEY idx_product_feedback_event_target(new_handler_user_id,created_at),
  CONSTRAINT fk_product_feedback_event_request FOREIGN KEY(request_id) REFERENCES t_product_feedback_request(id),
  CONSTRAINT fk_product_feedback_event_actor FOREIGN KEY(actor_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_product_feedback_event_previous_handler FOREIGN KEY(previous_handler_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT fk_product_feedback_event_new_handler FOREIGN KEY(new_handler_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT chk_product_feedback_event_type CHECK(event_type IN
    ('CREATED','ACCEPTED','ASSIGNED','STATUS_CHANGED','SUBMITTED_FOR_ACCEPTANCE','ACCEPTANCE_CONFIRMED','ACCEPTANCE_REJECTED','COMMENTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='需求反馈不可覆盖处理与验收记录';

CREATE TABLE t_channel_view_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_channel VARCHAR(30) NOT NULL,
  config_version BIGINT NOT NULL,
  config_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  field_config JSON NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_by BIGINT DEFAULT NULL,
  published_at DATETIME(3) DEFAULT NULL,
  UNIQUE KEY uk_channel_view_config_version(source_channel,config_version),
  KEY idx_channel_view_config_status(source_channel,config_status,created_at),
  CONSTRAINT fk_channel_view_config_creator FOREIGN KEY(created_by) REFERENCES t_auth_user(id),
  CONSTRAINT fk_channel_view_config_publisher FOREIGN KEY(published_by) REFERENCES t_auth_user(id),
  CONSTRAINT chk_channel_view_config_status CHECK(config_status IN ('DRAFT','PUBLISHED','SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='渠道默认字段展示配置及发布历史';

CREATE TABLE t_channel_acceptance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_channel VARCHAR(30) NOT NULL,
  acceptance_status VARCHAR(30) NOT NULL,
  acceptance_comment VARCHAR(1000) NOT NULL,
  accepted_by BIGINT NOT NULL,
  accepted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_channel_acceptance_channel(source_channel,accepted_at,id),
  CONSTRAINT fk_channel_acceptance_actor FOREIGN KEY(accepted_by) REFERENCES t_auth_user(id),
  CONSTRAINT chk_channel_acceptance_status CHECK(acceptance_status IN ('PASSED','CONDITIONAL','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='渠道负责人页面验收结论历史';

-- The governed physical business table count is now 52.
