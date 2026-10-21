INSERT INTO t_auth_permission(permission_code,permission_name,permission_type,parent_code,resource_path,sort_order)
VALUES('action:inspect_masking_raw','按工单受控查验脱敏原文','BUTTON','INGESTION','POST /api/v1/masking-security/raw-inspection',125)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_path=VALUES(resource_path),sort_order=VALUES(sort_order);

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) VALUES
('ADMIN','action:inspect_masking_raw'),('OPERATOR','action:inspect_masking_raw');
