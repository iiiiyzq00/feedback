UPDATE t_system_config_catalog
SET description='SDK 聚合接入速率占所选窗口活跃应用限流预算的参考比例；非主动告警规则'
WHERE config_key='sdk.rate-warning-utilization';

UPDATE t_system_config
SET config_desc='SDK 接入预算参考比例',change_reason='按所选窗口活跃应用计算；与主动告警规则区分'
WHERE config_key='sdk.rate-warning-utilization' AND is_active=TRUE;

UPDATE t_metric_definition
SET definition_text='SDK 回执 RPS；参考线=所选窗口活跃应用数×每应用分钟限额×70%÷60；不主动告警'
WHERE metric_code='SDK_INGEST_RPS';
