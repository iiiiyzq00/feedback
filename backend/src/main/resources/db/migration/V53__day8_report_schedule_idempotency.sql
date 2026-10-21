ALTER TABLE t_ai_report
  ADD COLUMN idempotency_key VARCHAR(191) DEFAULT NULL AFTER generation_source,
  ADD COLUMN previous_report_id BIGINT DEFAULT NULL AFTER idempotency_key,
  ADD COLUMN regeneration_no INT NOT NULL DEFAULT 0 AFTER previous_report_id,
  ADD UNIQUE KEY uk_report_idempotency_key (idempotency_key),
  ADD KEY idx_report_previous (previous_report_id);

CREATE TABLE t_report_schedule_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_key VARCHAR(100) NOT NULL,
  report_type VARCHAR(30) NOT NULL,
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  run_status VARCHAR(20) NOT NULL,
  lock_acquired BOOLEAN NOT NULL DEFAULT FALSE,
  report_id BIGINT DEFAULT NULL,
  error_code VARCHAR(100) DEFAULT NULL,
  error_message VARCHAR(1000) DEFAULT NULL,
  started_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_report_schedule_status (run_status, started_at),
  KEY idx_report_schedule_business (schedule_key, period_start, period_end),
  CONSTRAINT chk_report_schedule_status CHECK (run_status IN ('RUNNING','SUCCEEDED','FAILED','SKIPPED_LOCK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='报告调度执行历史';

CREATE TABLE t_report_generation_key (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  idempotency_key VARCHAR(191) NOT NULL,
  generation_source VARCHAR(20) NOT NULL,
  report_id BIGINT DEFAULT NULL,
  base_key VARCHAR(191) NOT NULL,
  regeneration_no INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) DEFAULT NULL,
  UNIQUE KEY uk_report_generation_key (idempotency_key),
  KEY idx_report_generation_base (base_key, regeneration_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='报告生成业务幂等键';
