ALTER TABLE t_ai_report
  ADD COLUMN report_task_id BIGINT DEFAULT NULL AFTER ai_task_id,
  ADD COLUMN prompt_sha256_snapshot CHAR(64) DEFAULT NULL AFTER prompt_version_snapshot,
  ADD COLUMN model_config_snapshot JSON DEFAULT NULL AFTER model_name_snapshot,
  ADD COLUMN provider_request_id VARCHAR(100) DEFAULT NULL AFTER model_config_snapshot,
  ADD COLUMN input_tokens INT DEFAULT NULL AFTER provider_request_id,
  ADD COLUMN output_tokens INT DEFAULT NULL AFTER input_tokens,
  ADD COLUMN latency_ms BIGINT DEFAULT NULL AFTER output_tokens,
  ADD COLUMN estimated_cost_usd DECIMAL(14,8) DEFAULT NULL AFTER latency_ms,
  ADD KEY idx_report_task (report_task_id);

CREATE TABLE t_report_generation_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL,
  request_snapshot JSON NOT NULL,
  generation_source VARCHAR(20) NOT NULL,
  actor_id BIGINT DEFAULT NULL,
  task_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  max_retries INT NOT NULL DEFAULT 3,
  lease_holder VARCHAR(100) DEFAULT NULL,
  lease_expires_at DATETIME(3) DEFAULT NULL,
  next_retry_at DATETIME(3) DEFAULT NULL,
  report_id BIGINT DEFAULT NULL,
  error_code VARCHAR(100) DEFAULT NULL,
  error_detail VARCHAR(1000) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at DATETIME(3) DEFAULT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_report_task_public (public_id),
  KEY idx_report_task_claim (task_status,next_retry_at,lease_expires_at,id),
  CONSTRAINT chk_report_task_status CHECK (task_status IN ('PENDING','RUNNING','RETRY_PENDING','COMPLETED','FAILED')),
  CONSTRAINT chk_report_task_retry CHECK (retry_count>=0 AND max_retries BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='报告生成持久化任务';

CREATE TABLE t_report_ai_health (
  health_key VARCHAR(50) PRIMARY KEY,
  circuit_state VARCHAR(20) NOT NULL DEFAULT 'CLOSED',
  consecutive_failures INT NOT NULL DEFAULT 0,
  next_probe_at DATETIME(3) DEFAULT NULL,
  last_failure_code VARCHAR(100) DEFAULT NULL,
  last_failure_at DATETIME(3) DEFAULT NULL,
  last_success_at DATETIME(3) DEFAULT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_report_ai_circuit CHECK (circuit_state IN ('CLOSED','OPEN','HALF_OPEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='报告AI降级断路状态';

INSERT INTO t_report_ai_health(health_key) VALUES('REPORT_NARRATION');
