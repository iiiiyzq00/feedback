-- One explicit grant for the two phase-one business modules. Specific Feishu
-- identities receive PHASE_ONE_USER through the governed stable-ID mapping.
INSERT INTO t_auth_permission(
  permission_code,permission_name,permission_type,parent_code,resource_path,sort_order
) VALUES(
  'access:phase_one_workspace','一期渠道数据与问题深度分析','MENU',NULL,
  '/channel/**,/dashboard/analysis',2220
)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),
  permission_type=VALUES(permission_type),
  resource_path=VALUES(resource_path),
  sort_order=VALUES(sort_order);

INSERT INTO t_auth_role(role_code,role_name,role_desc,is_active)
VALUES('PHASE_ONE_USER','一期授权用户','仅可查看一期四渠道数据、受控证据和问题深度分析',TRUE)
ON DUPLICATE KEY UPDATE
  role_name=VALUES(role_name),role_desc=VALUES(role_desc),is_active=TRUE;

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) VALUES
  ('PHASE_ONE_USER','access:phase_one_workspace'),
  ('ADMIN','access:phase_one_workspace');

INSERT IGNORE INTO t_auth_data_permission(
  subject_type,subject_id,scope_type,scope_value,access_level,effect
) VALUES
  ('ROLE','PHASE_ONE_USER','CHANNEL','STATION_FEEDBACK','READ','ALLOW'),
  ('ROLE','PHASE_ONE_USER','CHANNEL','JIRA','READ','ALLOW'),
  ('ROLE','PHASE_ONE_USER','CHANNEL','QUALITY','READ','ALLOW'),
  ('ROLE','PHASE_ONE_USER','CHANNEL','REDBLACK','READ','ALLOW');
