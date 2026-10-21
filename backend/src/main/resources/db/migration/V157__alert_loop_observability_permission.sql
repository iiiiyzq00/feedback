-- Project goal 8: expose the read-only alert-loop health view to existing
-- governance roles. No new operational role is introduced.
INSERT INTO t_auth_permission(permission_code,permission_name,permission_type,parent_code,resource_path,sort_order)
VALUES ('access:alert_loop_health','查看预警运行闭环健康度','BUTTON','ALERT',
        'GET /api/v1/admin/alert-rules/loop-health',625)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type),
 parent_code=VALUES(parent_code),resource_path=VALUES(resource_path),sort_order=VALUES(sort_order);

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) VALUES
('ADMIN','access:alert_loop_health'),
('AUDITOR','access:alert_loop_health');
