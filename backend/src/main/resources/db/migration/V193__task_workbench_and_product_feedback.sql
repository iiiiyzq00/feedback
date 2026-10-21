ALTER TABLE t_feedback_disposition_event
  DROP CHECK chk_feedback_disposition_type,
  ADD COLUMN outcome_code VARCHAR(40) DEFAULT NULL AFTER new_status,
  ADD CONSTRAINT chk_feedback_disposition_type CHECK(event_type IN (
    'CLAIMED','HANDOFF','HANDOFF_ACCEPTED','HANDOFF_CANCELLED','PROCESSED','STATUS_CHANGED','REOPENED'
  )),
  ADD CONSTRAINT chk_feedback_disposition_outcome CHECK(outcome_code IS NULL OR outcome_code IN (
    'RESOLVED','DEFERRED','CANNOT_REPRODUCE','DUPLICATE','NO_ACTION','KNOWLEDGE_CANDIDATE'
  ));

CREATE TABLE t_product_feedback_request (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_no VARCHAR(40) NOT NULL,
  request_type VARCHAR(30) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  source_channel VARCHAR(30) DEFAULT NULL,
  feedback_source_id BIGINT DEFAULT NULL,
  page_path VARCHAR(500) NOT NULL,
  page_context JSON DEFAULT NULL,
  attachment_metadata JSON DEFAULT NULL,
  request_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  created_by BIGINT NOT NULL,
  handler_user_id BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_product_feedback_request_no (request_no),
  KEY idx_product_feedback_status (request_status,created_at),
  KEY idx_product_feedback_creator (created_by,created_at),
  CONSTRAINT fk_product_feedback_source FOREIGN KEY(feedback_source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT fk_product_feedback_creator FOREIGN KEY(created_by) REFERENCES t_auth_user(id),
  CONSTRAINT fk_product_feedback_handler FOREIGN KEY(handler_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT chk_product_feedback_type CHECK(request_type IN ('FIELD_MISSING','DISPLAY','WORKFLOW','PERMISSION','DATA_ISSUE','NEW_REQUIREMENT')),
  CONSTRAINT chk_product_feedback_status CHECK(request_status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='渠道负责人页面问题与产品需求反馈';

-- The governed physical business table count is now 49.
