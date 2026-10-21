CREATE TABLE t_jira_attachment_pull_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  feedback_source_id BIGINT NOT NULL,
  jira_key VARCHAR(100) NOT NULL,
  jira_updated_at DATETIME(3) NOT NULL,
  task_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  max_retries INT NOT NULL DEFAULT 5,
  next_retry_at DATETIME(3) DEFAULT NULL,
  lease_holder VARCHAR(100) DEFAULT NULL,
  lease_expires_at DATETIME(3) DEFAULT NULL,
  last_error_code VARCHAR(100) DEFAULT NULL,
  last_error_detail VARCHAR(500) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) DEFAULT NULL,
  UNIQUE KEY uk_jira_attachment_source (feedback_source_id),
  KEY idx_jira_attachment_claim (task_status,next_retry_at,lease_expires_at,id),
  CONSTRAINT fk_jira_attachment_source FOREIGN KEY (feedback_source_id) REFERENCES t_feedback_source(id),
  CONSTRAINT chk_jira_attachment_task_status CHECK (task_status IN ('PENDING','PROCESSING','RETRY_PENDING','COMPLETED','FAILED')),
  CONSTRAINT chk_jira_attachment_retry CHECK (retry_count >= 0 AND max_retries BETWEEN 1 AND 20)
);

-- The governed physical business table count is now 57.
