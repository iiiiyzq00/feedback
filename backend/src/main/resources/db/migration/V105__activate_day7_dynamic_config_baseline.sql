-- V49 established the editable catalog but did not create current values for
-- most catalog entries.  A catalog item without an active value is invisible
-- to the dynamic-config API, so initialise the missing non-secret baselines.
INSERT INTO t_system_config(
  config_key,
  config_version,
  config_value,
  config_desc,
  is_active,
  change_set_id,
  change_reason
)
SELECT defaults.config_key,
       'v1',
       CAST(defaults.config_value AS JSON),
       defaults.config_desc,
       TRUE,
       UUID(),
       'V105补齐Day 7动态配置基线'
FROM (
  SELECT 'sdk.sign-window-seconds' config_key, '300' config_value, '签名允许时间窗口（秒）' config_desc UNION ALL
  SELECT 'sdk.rate-limit', '100', '每个应用请求速率上限' UNION ALL
  SELECT 'sdk.nonce-expire-minutes', '5', 'Nonce去重保留时间（分钟）' UNION ALL
  SELECT 'attachment.max-mb', '20', '单附件大小上限（MB）' UNION ALL
  SELECT 'cipher.retention-days', '7', '待处理密文保留天数' UNION ALL
  SELECT 'cluster.top-k', '10', '归簇候选召回数量' UNION ALL
  SELECT 'ai.timeout-seconds', '45', '外部模型调用超时（秒）' UNION ALL
  SELECT 'import.rollback-hours', '24', '导入批次回滚窗口（小时）'
) defaults
WHERE EXISTS (
  SELECT 1
  FROM t_system_config_catalog catalog
  WHERE catalog.config_key=defaults.config_key
)
AND NOT EXISTS (
  SELECT 1
  FROM t_system_config current_config
  WHERE current_config.config_key=defaults.config_key
    AND current_config.is_active=TRUE
);
