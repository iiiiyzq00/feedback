-- D7-G01: latest v6 governance role model replaces legacy ANALYST with AUDITOR.
INSERT INTO t_auth_role(role_code,role_name,role_desc,is_active)
VALUES('AUDITOR','审计员','渠道与看板只读、脱敏规则和操作审计只读',TRUE)
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),role_desc=VALUES(role_desc),is_active=TRUE;

INSERT IGNORE INTO t_auth_user_role(user_id,role_code,assigned_by,assigned_at,expires_at)
SELECT user_id,'AUDITOR',assigned_by,assigned_at,expires_at FROM t_auth_user_role WHERE role_code='ANALYST';
DELETE FROM t_auth_user_role WHERE role_code='ANALYST';

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code)
SELECT 'AUDITOR',permission_code FROM t_auth_role_permission
WHERE role_code='ANALYST' AND permission_code IN ('feedback:read','dashboard:read');
DELETE FROM t_auth_role_permission WHERE role_code='ANALYST';

INSERT IGNORE INTO t_auth_data_permission(subject_type,subject_id,scope_type,scope_value,access_level,effect)
SELECT subject_type,'AUDITOR',scope_type,scope_value,access_level,effect
FROM t_auth_data_permission WHERE subject_type='ROLE' AND subject_id='ANALYST';
DELETE FROM t_auth_data_permission WHERE subject_type='ROLE' AND subject_id='ANALYST';
DELETE FROM t_auth_role WHERE role_code='ANALYST';

-- D7-G02: the governed catalog in section 3.8.4 is the only rule-count source.
INSERT INTO t_system_config(config_key,config_version,config_value,config_desc,is_active)
SELECT 'alert.governance.expected-rule-count','v1',CAST('34' AS JSON),'方案3.8.4正式业务规则数',TRUE
WHERE NOT EXISTS(SELECT 1 FROM t_system_config WHERE config_key='alert.governance.expected-rule-count' AND is_active=TRUE);

-- D7-G03: canonical knowledge lifecycle is five-state only.
UPDATE t_ai_knowledge SET knowledge_status='DEPRECATED' WHERE knowledge_status IN ('ARCHIVED','RETIRED');
ALTER TABLE t_ai_knowledge DROP CHECK chk_knowledge_status;
ALTER TABLE t_ai_knowledge ADD CONSTRAINT chk_knowledge_status
  CHECK (knowledge_status IN ('DRAFT','PENDING_REVIEW','PUBLISHED','REJECTED','DEPRECATED'));
