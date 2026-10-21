ALTER TABLE t_import_row
  ADD COLUMN privacy_review_decision VARCHAR(30) NULL AFTER quality_suggestion,
  ADD COLUMN privacy_review_reason VARCHAR(500) NULL AFTER privacy_review_decision,
  ADD COLUMN privacy_reviewed_by BIGINT NULL AFTER privacy_review_reason,
  ADD COLUMN privacy_reviewed_at DATETIME(3) NULL AFTER privacy_reviewed_by,
  ADD COLUMN privacy_review_rule_version VARCHAR(50) NULL AFTER privacy_reviewed_at,
  ADD COLUMN privacy_review_input_sha256 BINARY(32) NULL AFTER privacy_review_rule_version,
  ADD COLUMN privacy_review_output_sha256 BINARY(32) NULL AFTER privacy_review_input_sha256,
  ADD COLUMN privacy_review_findings JSON NULL AFTER privacy_review_output_sha256,
  ADD CONSTRAINT chk_import_privacy_review_decision CHECK (
    privacy_review_decision IS NULL OR privacy_review_decision IN
      ('FALSE_POSITIVE_RELEASE','REMASK_AND_RELEASE','KEEP_QUARANTINED')
  ),
  ADD KEY idx_import_privacy_review_source (feedback_source_id,privacy_review_decision);

INSERT INTO t_auth_permission(permission_code,permission_name,permission_type,parent_code,resource_path,sort_order)
VALUES('action:review_masking_raw','人工判定脱敏隔离数据','BUTTON','INGESTION','POST /api/v1/masking-security/raw-review',126)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type),
  parent_code=VALUES(parent_code),resource_path=VALUES(resource_path),sort_order=VALUES(sort_order);

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code)
SELECT rp.role_code,'action:review_masking_raw'
FROM t_auth_role_permission rp
WHERE rp.permission_code='action:inspect_masking_raw';
