-- Preserve risk-brief notification delivery evidence when a report is removed from the product UI.
ALTER TABLE t_ai_report
  ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0 AFTER report_status,
  ADD COLUMN deleted_by BIGINT DEFAULT NULL AFTER is_deleted,
  ADD COLUMN deleted_at DATETIME(3) DEFAULT NULL AFTER deleted_by,
  ADD COLUMN deletion_reason VARCHAR(500) DEFAULT NULL AFTER deleted_at,
  ADD KEY idx_report_visible (is_deleted,report_type,generated_at),
  ADD CONSTRAINT chk_report_soft_delete CHECK (
    (is_deleted=0 AND deleted_by IS NULL AND deleted_at IS NULL AND deletion_reason IS NULL)
    OR (is_deleted=1 AND deleted_by IS NOT NULL AND deleted_at IS NOT NULL AND deletion_reason IS NOT NULL)
  );
