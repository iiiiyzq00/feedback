INSERT INTO t_auth_permission(permission_code,permission_name,permission_type,resource_path,sort_order)
VALUES('access:masking_quarantine','查看脱敏隔离区','API','/api/v1/masking-security/quarantine',915)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_path=VALUES(resource_path);
INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) VALUES('ADMIN','access:masking_quarantine');
ALTER TABLE t_feedback_ai ADD COLUMN index_masking_rule_version VARCHAR(50) DEFAULT NULL AFTER embedding_model_version;
ALTER TABLE t_cluster ADD COLUMN index_masking_rule_version VARCHAR(50) DEFAULT NULL AFTER vector_index_status;
