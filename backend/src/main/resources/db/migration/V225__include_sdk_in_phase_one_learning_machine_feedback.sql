-- SDK and the legacy Station channel remain separate ingestion and audit facts,
-- but both are visible in the phase-one learning-machine feedback product view.
INSERT IGNORE INTO t_auth_data_permission(
  subject_type,subject_id,scope_type,scope_value,access_level,effect
) VALUES(
  'ROLE','PHASE_ONE_USER','CHANNEL','SDK','READ','ALLOW'
);

UPDATE t_auth_role
SET role_desc='仅可查看一期学习机（SDK及原反馈平台）、Jira、质量平台、红黑榜数据、受控证据和问题深度分析'
WHERE role_code='PHASE_ONE_USER';
