INSERT INTO t_auth_permission(permission_code,permission_name,permission_type,parent_code,resource_path,sort_order) VALUES
('access:ingestion_monitor','访问数据接入监控','MENU','INGESTION','/monitor/overview',100),
('access:import_management','访问表格导入管理','MENU','INGESTION','/monitor/import',110),
('action:upload_file','上传表格文件','BUTTON','INGESTION','POST /api/v1/import/batches',120),
('access:channel_sdk','访问SDK反馈','MENU','CHANNEL','/channel/sdk',200),
('access:channel_jira','访问Jira列表','MENU','CHANNEL','/channel/jira',210),
('access:channel_quality','访问质量平台','MENU','CHANNEL','/channel/quality',220),
('access:channel_redblack','访问红黑榜','MENU','CHANNEL','/channel/redblack',230),
('action:decrypt_contact','按工单解密已授权联系方式','BUTTON','CHANNEL','POST /api/v1/feedback/*/contact/decrypt',240),
('action:export_data','导出数据','BUTTON','CHANNEL','GET /api/v1/feedback/export',250),
('action:edit_status','修改处理状态','BUTTON','CHANNEL','PUT /api/v1/feedback/*/status',260),
('access:dashboard_overview','访问总览看板','MENU','DASHBOARD','/dashboard/overview',300),
('access:dashboard_analysis','访问问题深度分析','MENU','DASHBOARD','/dashboard/analysis',310),
('access:alert_center','访问告警中心','MENU','ALERT','/alert/center',400),
('action:ack_alert','确认、处理和转派告警','BUTTON','ALERT','POST /api/v1/alerts/*/ack',410),
('access:ai_reports','访问AI报告','MENU','REPORT','/report/list',500),
('access:user_management','用户与权限管理','MENU','SYSTEM','/admin/users',600),
('access:system_config','系统配置管理','MENU','SYSTEM','/admin/system',610),
('access:routing_config','告警路由规则配置','MENU','SYSTEM','/admin/alerts',620),
('access:oncall_view','查看值班排班','MENU','SYSTEM','/admin/oncall',630),
('access:oncall_edit_own','管理本团队排班','BUTTON','SYSTEM','PATCH /api/v1/oncall/own',640),
('access:oncall_edit_all','管理所有团队排班','BUTTON','SYSTEM','PATCH /api/v1/oncall/all',650),
('access:masking_view','查看脱敏规则','MENU','SYSTEM','/admin/masking',660),
('access:masking_edit','编辑脱敏规则','BUTTON','SYSTEM','PUT /api/v1/admin/masking-rules/*',670),
('access:audit_logs','操作审计日志','MENU','SYSTEM','/admin/audit-log',680)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),permission_type=VALUES(permission_type),parent_code=VALUES(parent_code),resource_path=VALUES(resource_path),sort_order=VALUES(sort_order);

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) SELECT 'ADMIN',permission_code FROM t_auth_permission;
INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) VALUES
('OPERATOR','access:ingestion_monitor'),('OPERATOR','access:import_management'),('OPERATOR','action:upload_file'),
('OPERATOR','access:channel_sdk'),('OPERATOR','access:channel_jira'),('OPERATOR','access:channel_quality'),('OPERATOR','access:channel_redblack'),
('OPERATOR','action:export_data'),('OPERATOR','action:edit_status'),('OPERATOR','access:dashboard_overview'),('OPERATOR','access:dashboard_analysis'),
('OPERATOR','access:alert_center'),('OPERATOR','action:ack_alert'),('OPERATOR','access:ai_reports'),('OPERATOR','access:oncall_view'),('OPERATOR','access:oncall_edit_own'),
('AUDITOR','access:channel_sdk'),('AUDITOR','access:channel_jira'),('AUDITOR','access:channel_quality'),('AUDITOR','access:channel_redblack'),
('AUDITOR','access:dashboard_overview'),('AUDITOR','access:dashboard_analysis'),('AUDITOR','access:masking_view'),('AUDITOR','access:audit_logs'),
('USER','access:channel_sdk'),('USER','access:dashboard_overview');

INSERT IGNORE INTO t_auth_data_permission(subject_type,subject_id,scope_type,scope_value,access_level,effect) VALUES
('ROLE','OPERATOR','CHANNEL','*','READ','ALLOW'),('ROLE','AUDITOR','CHANNEL','*','READ','ALLOW'),('ROLE','USER','CHANNEL','SDK','READ','ALLOW');

ALTER TABLE t_auth_data_permission ADD UNIQUE KEY uk_auth_data_scope(subject_type,subject_id,scope_type,scope_value,access_level,effect);
