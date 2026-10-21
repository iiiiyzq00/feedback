ALTER TABLE t_report_generation_task
  DROP CHECK chk_report_task_status,
  ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE AFTER task_status,
  ADD CONSTRAINT chk_report_task_status CHECK (task_status IN ('PENDING','RUNNING','RETRY_PENDING','COMPLETED','FAILED','CANCELLED'));
