-- 上线前告警规则处置（2026-08-07）：
-- 1) 平台通知自监控 4 条（SYSTEM_NOTIFY_*）纳入治理目录并启用。
--    原因：AlertEngine 对不在治理目录的规则会经 enforceRuntimeReady 自动禁用；
--    生产必须保留的自监控规则必须成为受控目录规则（SYSTEM 类、IMPLEMENTED、无外部契约依赖）。
-- 2) 退役 legacy 阈值规则 DATA_QUALITY_THRESHOLD_BREACH（已被 DATA_QUALITY_DROP 等替代）。
-- 3) 清理开发期通用草稿 RULE_*（UI 测试创建，均无实例/任务/通知引用）。
-- 治理目录期望数量：53 → 57（新增 4 条 SYSTEM_NOTIFY 自监控）。

-- 1) 纳入治理目录（t_system_config 承载），沿用 V165 对风险规则直接登记 IMPLEMENTED 的模式。
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,approval_status,owner_team,config_desc,is_active,created_at,updated_at)
VALUES
(CONCAT('ALERT_RULE_CATALOG:','SYSTEM_NOTIFY_BACKLOG'),'ALERT_RULE_CATALOG','system-notify-v1',
 JSON_OBJECT('ruleCode','SYSTEM_NOTIFY_BACKLOG','ruleName','通知Outbox积压','category','SYSTEM','defaultSeverity','P2','ownerTeam','平台','evaluationMode','SCHEDULED','implementationStatus','IMPLEMENTED','implementationEvidenceSha256',SHA2('system-notify-v1:SYSTEM_NOTIFY_BACKLOG',256),'governanceStatus','APPROVED','parameterTemplate',JSON_OBJECT('metric','NOTIFICATION_BACKLOG','window','5m','threshold',100),'recoveryPolicy','积压低于阈值后恢复','dataDependencies',JSON_ARRAY(),'p0NonSilenceable',0,'sourceReference','平台自监控','builtIn',TRUE),
 'alert-rule-catalog-v1','APPROVED','APPROVED','平台','通知Outbox积压自监控',1,NOW(3),NOW(3)),
(CONCAT('ALERT_RULE_CATALOG:','SYSTEM_NOTIFY_DEAD_LETTER'),'ALERT_RULE_CATALOG','system-notify-v1',
 JSON_OBJECT('ruleCode','SYSTEM_NOTIFY_DEAD_LETTER','ruleName','通知重试耗尽进入死信','category','SYSTEM','defaultSeverity','P1','ownerTeam','平台','evaluationMode','SCHEDULED','implementationStatus','IMPLEMENTED','implementationEvidenceSha256',SHA2('system-notify-v1:SYSTEM_NOTIFY_DEAD_LETTER',256),'governanceStatus','APPROVED','parameterTemplate',JSON_OBJECT('metric','NOTIFICATION_DEAD_LETTER','window','5m','threshold',1),'recoveryPolicy','死信清零后恢复','dataDependencies',JSON_ARRAY(),'p0NonSilenceable',0,'sourceReference','平台自监控','builtIn',TRUE),
 'alert-rule-catalog-v1','APPROVED','APPROVED','平台','通知死信自监控',1,NOW(3),NOW(3)),
(CONCAT('ALERT_RULE_CATALOG:','SYSTEM_NOTIFY_FAILURE_RATE'),'ALERT_RULE_CATALOG','system-notify-v1',
 JSON_OBJECT('ruleCode','SYSTEM_NOTIFY_FAILURE_RATE','ruleName','通知投递失败率过高','category','SYSTEM','defaultSeverity','P1','ownerTeam','平台','evaluationMode','SCHEDULED','implementationStatus','IMPLEMENTED','implementationEvidenceSha256',SHA2('system-notify-v1:SYSTEM_NOTIFY_FAILURE_RATE',256),'governanceStatus','APPROVED','parameterTemplate',JSON_OBJECT('metric','NOTIFICATION_FAILURE_RATE','window','5m','threshold',20),'recoveryPolicy','失败率低于阈值后恢复','dataDependencies',JSON_ARRAY(),'p0NonSilenceable',0,'sourceReference','平台自监控','builtIn',TRUE),
 'alert-rule-catalog-v1','APPROVED','APPROVED','平台','通知失败率自监控',1,NOW(3),NOW(3)),
(CONCAT('ALERT_RULE_CATALOG:','SYSTEM_NOTIFY_OLDEST_PENDING'),'ALERT_RULE_CATALOG','system-notify-v1',
 JSON_OBJECT('ruleCode','SYSTEM_NOTIFY_OLDEST_PENDING','ruleName','通知最老待处理消息超时','category','SYSTEM','defaultSeverity','P1','ownerTeam','平台','evaluationMode','SCHEDULED','implementationStatus','IMPLEMENTED','implementationEvidenceSha256',SHA2('system-notify-v1:SYSTEM_NOTIFY_OLDEST_PENDING',256),'governanceStatus','APPROVED','parameterTemplate',JSON_OBJECT('metric','NOTIFICATION_OLDEST_PENDING','window','5m','threshold',300),'recoveryPolicy','最老待处理低于阈值后恢复','dataDependencies',JSON_ARRAY(),'p0NonSilenceable',0,'sourceReference','平台自监控','builtIn',TRUE),
 'alert-rule-catalog-v1','APPROVED','APPROVED','平台','通知最老待处理自监控',1,NOW(3),NOW(3))
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),config_schema_version=VALUES(config_schema_version),
 lifecycle_status='APPROVED',approval_status='APPROVED',owner_team=VALUES(owner_team),config_desc=VALUES(config_desc),is_active=1,updated_at=NOW(3);

-- 2) 平台通知自监控：启用（纳入目录后不再被 enforceRuntimeReady 自动禁用）。
UPDATE t_alert_rule
SET is_enabled = 1, updated_at = NOW(3)
WHERE version_status = 'ACTIVE'
  AND rule_code IN ('SYSTEM_NOTIFY_BACKLOG','SYSTEM_NOTIFY_DEAD_LETTER',
                    'SYSTEM_NOTIFY_FAILURE_RATE','SYSTEM_NOTIFY_OLDEST_PENDING');

-- 3) legacy 数据质量阈值规则退役（保留历史实例，不再评估）。
UPDATE t_alert_rule
SET version_status = 'RETIRED', is_enabled = 0, updated_at = NOW(3)
WHERE version_status = 'ACTIVE' AND rule_code = 'DATA_QUALITY_THRESHOLD_BREACH';

-- 4) 清理开发期通用草稿：仅删除无任何运行引用的行，保留审计历史由数据保留策略管理。
DELETE FROM t_alert_rule
WHERE rule_code LIKE 'RULE\_%'
  AND NOT EXISTS (SELECT 1 FROM t_alert_instance i WHERE i.rule_id = t_alert_rule.id)
  AND NOT EXISTS (SELECT 1 FROM t_ai_task a WHERE a.rule_id = t_alert_rule.id)
  AND NOT EXISTS (SELECT 1 FROM t_alert_notification n WHERE n.rule_id = t_alert_rule.id);

-- 5) 治理目录期望数量：53 → 57。
UPDATE t_system_config
SET config_value = CAST('57' AS JSON),
    config_desc = '正式治理规则数（含 SYSTEM_NOTIFY 平台自监控 4 条）',
    updated_at = NOW(3)
WHERE config_key = 'alert.governance.expected-rule-count'
  AND is_active = 1;
