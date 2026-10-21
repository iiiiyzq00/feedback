-- 安全左移治理（2026-08-07）：预防优先、检测兜底。
-- 给安全/脱敏域规则补充 preventionClass 与设计期规避措施：
--   A = 设计可规避（告警触发即设计事故，零容忍，必须登记 automatedGates）
--   B = 设计降低概率 + 检测兜底（准入/审核/最小化，残余风险靠告警）
--   C = 物理世界事件（设计无法消除，告警是主防线，人工闭环）
-- 治理目录期望数量保持 53，不新增规则。

-- A 类：设计可规避，零容忍
UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','A',
  '$.preventionMeasures',JSON_ARRAY('密钥只存 Keychain/环境变量，禁止入库','CI 密钥扫描门禁','密钥定期轮换'),
  '$.automatedGates',JSON_ARRAY('CI_SECRET_SCAN','SECRET_STORAGE_POLICY')),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='KEY_MATERIAL_EXPOSURE';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','A',
  '$.preventionMeasures',JSON_ARRAY('RBAC 最小权限','行级/字段级权限','越权矩阵自动化测试'),
  '$.automatedGates',JSON_ARRAY('RBAC_MATRIX_TEST','FIELD_LEVEL_PERMISSION_TEST')),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='UNAUTHORIZED_ACCESS_DETECTED';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','A',
  '$.preventionMeasures',JSON_ARRAY('脱敏默认 PASS 后才可查询','NER 白名单审核','脱敏覆盖率门禁'),
  '$.automatedGates',JSON_ARRAY('MASKING_PASS_GATE','NER_ALLOWLIST_REVIEW')),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='SENSITIVE_DATA_RESIDUAL_BLOCKED';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','A',
  '$.preventionMeasures',JSON_ARRAY('加密存储（ContactStorageCrypto）','字段级权限','数据最小化','导出审批'),
  '$.automatedGates',JSON_ARRAY('ENCRYPTION_REQUIRED','FIELD_LEVEL_PERMISSION_TEST')),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='SENSITIVE_DATA_EXPOSURE_CONFIRMED';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','A',
  '$.preventionMeasures',JSON_ARRAY('脱敏管线质量门禁','脱敏失败隔离不阻断查询'),
  '$.automatedGates',JSON_ARRAY('MASKING_QUALITY_GATE')),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='MASKING_FAILURE_RATE_HIGH';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','A',
  '$.preventionMeasures',JSON_ARRAY('规则版本上线前 F1/高敏召回评估门禁'),
  '$.automatedGates',JSON_ARRAY('MASKING_EVALUATION_GATE')),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='MASKING_RULE_QUALITY_DEGRADED';

-- B 类：设计降低概率 + 检测兜底
UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','B',
  '$.preventionMeasures',JSON_ARRAY('最小化收集','脱敏默认','授权留存','导出审批'),
  '$.automatedGates',JSON_ARRAY()),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='USER_PRIVACY_EXPOSURE';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','B',
  '$.preventionMeasures',JSON_ARRAY('内容供应链准入','上架前审核','AI 内容生成护栏'),
  '$.automatedGates',JSON_ARRAY('CONTENT_SUPPLY_ACCEPTANCE')),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='CHILD_CONTENT_SAFETY';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','B',
  '$.preventionMeasures',JSON_ARRAY('跨渠道信号汇总检测','高危簇人工复核'),
  '$.automatedGates',JSON_ARRAY()),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='HIGH_RISK_CROSS_CHANNEL';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','B',
  '$.preventionMeasures',JSON_ARRAY('内容审核与勘误流程','内容版本上线观察'),
  '$.automatedGates',JSON_ARRAY()),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='CONTENT_ERROR_SPIKE';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','B',
  '$.preventionMeasures',JSON_ARRAY('队列容量与限流设计','积压水位监控'),
  '$.automatedGates',JSON_ARRAY()),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='MASKING_PIPELINE_BACKLOG';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','B',
  '$.preventionMeasures',JSON_ARRAY('审核人力配置','SLA 流程与值班兜底'),
  '$.automatedGates',JSON_ARRAY()),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='MASKING_REVIEW_SLA_BREACH';

-- C 类：物理世界事件，告警是主防线
UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','C',
  '$.preventionMeasures',JSON_ARRAY('出厂质检','电池/充电安全设计','温控保护','物料认证'),
  '$.automatedGates',JSON_ARRAY()),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='DEVICE_SAFETY_STRONG_EVIDENCE';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','C',
  '$.preventionMeasures',JSON_ARRAY('出厂质检','电池/充电安全设计','温控保护','物料认证'),
  '$.automatedGates',JSON_ARRAY()),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='DEVICE_SAFETY_SUSPECTED';

UPDATE t_system_config SET config_value=JSON_SET(config_value,
  '$.preventionClass','C',
  '$.preventionMeasures',JSON_ARRAY('用户上报安全信号即时响应','客服前置安抚与换机通道'),
  '$.automatedGates',JSON_ARRAY()),
  updated_at=NOW(3)
WHERE config_type='ALERT_RULE_CATALOG' AND is_active=1 AND JSON_UNQUOTE(JSON_EXTRACT(config_value,'$.ruleCode'))='USER_SAFETY_IMMINENT';
