-- Split the former phase-one account into least-privilege product and technical roles.
-- Existing PHASE_ONE_USER assignments become product access; raw originals require
-- the explicit technical permission.
INSERT INTO t_auth_permission(
  permission_code,permission_name,permission_type,parent_code,resource_path,sort_order
) VALUES(
  'access:phase_one_raw_content','一期原文直接查看','BUTTON','access:phase_one_workspace',
  '/api/v1/feedback/*/diagnostics',2280
)
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),permission_type=VALUES(permission_type),
  parent_code=VALUES(parent_code),resource_path=VALUES(resource_path),sort_order=VALUES(sort_order);

INSERT INTO t_auth_role(role_code,role_name,role_desc,is_active) VALUES
  ('PHASE_ONE_TECHNICAL','一期技术','一期渠道与分析；直接查看关键信息原文和分析证据',TRUE),
  ('PHASE_ONE_PRODUCT','一期产品','一期渠道与分析；查看局部隐藏后的关键信息原文和分析证据',TRUE)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),role_desc=VALUES(role_desc),is_active=TRUE;

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) VALUES
  ('PHASE_ONE_TECHNICAL','access:phase_one_workspace'),
  ('PHASE_ONE_TECHNICAL','access:phase_one_raw_content'),
  ('PHASE_ONE_PRODUCT','access:phase_one_workspace'),
  ('ADMIN','access:phase_one_raw_content');

INSERT IGNORE INTO t_auth_data_permission(subject_type,subject_id,scope_type,scope_value,access_level,effect)
SELECT 'ROLE','PHASE_ONE_TECHNICAL',scope_type,scope_value,access_level,effect
FROM t_auth_data_permission WHERE subject_type='ROLE' AND subject_id='PHASE_ONE_USER';
INSERT IGNORE INTO t_auth_data_permission(subject_type,subject_id,scope_type,scope_value,access_level,effect)
SELECT 'ROLE','PHASE_ONE_PRODUCT',scope_type,scope_value,access_level,effect
FROM t_auth_data_permission WHERE subject_type='ROLE' AND subject_id='PHASE_ONE_USER';

INSERT IGNORE INTO t_auth_user_role(user_id,role_code,assigned_by,assigned_at,expires_at)
SELECT user_id,'PHASE_ONE_PRODUCT',assigned_by,assigned_at,expires_at
FROM t_auth_user_role WHERE role_code='PHASE_ONE_USER';
DELETE FROM t_auth_user_role WHERE role_code='PHASE_ONE_USER';
UPDATE t_auth_role SET is_active=FALSE,role_desc='已由 PHASE_ONE_TECHNICAL / PHASE_ONE_PRODUCT 替代'
WHERE role_code='PHASE_ONE_USER';
