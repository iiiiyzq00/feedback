-- D7-L01..L10: formal action catalog, request correlation and append-only integrity fields.
ALTER TABLE t_system_audit_log
  ADD COLUMN audit_class VARCHAR(20) NOT NULL DEFAULT 'LEGACY' AFTER operation,
  ADD COLUMN outcome VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' AFTER audit_class,
  ADD COLUMN request_id CHAR(36) DEFAULT NULL AFTER target_id,
  ADD COLUMN detail_schema_version VARCHAR(20) NOT NULL DEFAULT 'v1' AFTER operation_detail,
  ADD COLUMN previous_event_hash CHAR(64) DEFAULT NULL AFTER detail_schema_version,
  ADD COLUMN event_hash CHAR(64) DEFAULT NULL AFTER previous_event_hash,
  ADD KEY idx_audit_request (request_id),
  ADD KEY idx_audit_business_query (audit_class,operation,outcome,created_at),
  ADD KEY idx_audit_target (target_type,target_id,created_at),
  ADD CONSTRAINT chk_audit_class CHECK (audit_class IN ('BUSINESS','SECURITY','QUERY','SYSTEM','LEGACY')),
  ADD CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS','DENIED','FAILED')),
  ADD CONSTRAINT chk_audit_hash_shape CHECK ((event_hash IS NULL AND previous_event_hash IS NULL) OR event_hash REGEXP '^[0-9a-f]{64}$');

CREATE TABLE t_system_audit_action_catalog (
  operation VARCHAR(50) PRIMARY KEY,
  audit_class VARCHAR(20) NOT NULL,
  description VARCHAR(200) NOT NULL,
  sensitive_action BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT chk_audit_action_class CHECK (audit_class IN ('BUSINESS','SECURITY','QUERY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='正式业务审计动作目录';

INSERT INTO t_system_audit_action_catalog(operation,audit_class,description,sensitive_action) VALUES
('CREATE_USER','BUSINESS','创建用户',0),('EDIT_USER','BUSINESS','编辑用户',0),('DISABLE_USER','BUSINESS','停用用户',0),
('CONFIG_UPDATE','BUSINESS','动态配置变更或回滚',0),('ROUTING_RULE_EDIT','BUSINESS','告警规则和路由变更',0),
('MASKING_RULE_EDIT','SECURITY','脱敏规则候选、评估或发布',1),('NER_WHITELIST_EDIT','SECURITY','NER白名单变更',1),
('ONCALL_EDIT','BUSINESS','值班排班变更',0),('CONTACT_DECRYPT','SECURITY','授权联系方式解密',1),
('ATTACHMENT_DECRYPT','SECURITY','附件解密访问',1),('QUERY_FEEDBACK','QUERY','反馈查询或搜索',0),
('VIEW_FEEDBACK_DETAIL','QUERY','查看反馈详情',0),('EDIT_STATUS','BUSINESS','修改反馈状态',0),
('MANUAL_CLUSTER_DECISION','BUSINESS','人工归簇决策',0),('MANUAL_CORRECTION','BUSINESS','人工修正AI或映射',0),
('ACK_ALERT','BUSINESS','确认、转派、静默或解决告警',0),('EXPORT_DATA','SECURITY','数据导出',1),
('BATCH_ROLLBACK','BUSINESS','导入批次回滚',0),('ROLE_PERMISSION_EDIT','SECURITY','角色权限变更',1),
('PROMPT_UPDATE','BUSINESS','AI Skill或Prompt变更',0);

DELIMITER $$
CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON t_system_audit_log FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AUDIT_LOG_APPEND_ONLY'; END$$
CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON t_system_audit_log FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AUDIT_LOG_APPEND_ONLY'; END$$
DELIMITER ;
