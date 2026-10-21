INSERT INTO t_auth_role (role_code, role_name, role_desc, is_active) VALUES
  ('ADMIN','系统管理员','全部功能、配置和高风险操作',TRUE),
  ('OPERATOR','运营处理员','导入、审核、告警和状态处理',TRUE),
  ('ANALYST','数据分析员','看板、查询和报告只读',TRUE),
  ('USER','普通用户','脱敏后的基础查询',TRUE)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), role_desc=VALUES(role_desc), is_active=TRUE;

INSERT INTO t_auth_permission
  (permission_code, permission_name, permission_type, resource_path, sort_order) VALUES
  ('feedback:read','反馈查询','API','/api/v1/feedback/**',10),
  ('feedback:write','反馈处理','API','/api/v1/feedback/batch-status',20),
  ('feedback:export','反馈导出','API','/api/v1/feedback/export',30),
  ('import:manage','导入管理','API','/api/v1/import/**',40),
  ('cluster:review','问题簇审核','API','/api/v1/cluster/review/**',50),
  ('dashboard:read','看板查询','API','/api/v1/dashboard/**',60),
  ('admin:manage','系统管理','API','/api/v1/admin/**',70)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name), resource_path=VALUES(resource_path);

INSERT IGNORE INTO t_auth_role_permission (role_code, permission_code)
SELECT 'ADMIN', permission_code FROM t_auth_permission;
INSERT IGNORE INTO t_auth_role_permission (role_code, permission_code) VALUES
  ('OPERATOR','feedback:read'),('OPERATOR','feedback:write'),('OPERATOR','feedback:export'),
  ('OPERATOR','import:manage'),('OPERATOR','cluster:review'),('OPERATOR','dashboard:read'),
  ('ANALYST','feedback:read'),('ANALYST','feedback:export'),('ANALYST','dashboard:read'),
  ('USER','feedback:read'),('USER','dashboard:read');

INSERT IGNORE INTO t_auth_data_permission
  (subject_type,subject_id,scope_type,scope_value,access_level,effect) VALUES
  ('ROLE','OPERATOR','CHANNEL','*','READ','ALLOW'),
  ('ROLE','ANALYST','CHANNEL','*','READ','ALLOW'),
  ('ROLE','USER','CHANNEL','SDK','READ','ALLOW');
