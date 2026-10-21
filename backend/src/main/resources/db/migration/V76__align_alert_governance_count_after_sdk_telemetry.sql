-- V74 added two governed SDK request telemetry rules to the closed catalog.
-- Keep the configured governance baseline aligned with the actual catalog.
UPDATE t_system_config
SET config_value = '36',
    config_desc = '正式治理规则数（含SDK入口遥测规则）',
    updated_at = NOW(3)
WHERE config_key = 'alert.governance.expected-rule-count'
  AND is_active = 1;
