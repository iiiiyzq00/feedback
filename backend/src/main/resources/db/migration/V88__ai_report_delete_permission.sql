INSERT INTO t_auth_permission(permission_code,permission_name,permission_type,resource_path,sort_order)
VALUES ('action:delete_reports','删除AI报告','BUTTON','DELETE /api/v1/reports/{id}',511)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type),resource_path=VALUES(resource_path),sort_order=VALUES(sort_order);

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code)
VALUES ('ADMIN','action:delete_reports');
