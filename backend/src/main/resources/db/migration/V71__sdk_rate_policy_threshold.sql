INSERT INTO t_system_config_catalog(config_key,value_type,min_value,max_value,owner_module,description)
VALUES ('sdk.rate-warning-utilization','DECIMAL',0.10,0.95,'INGESTION','SDK 聚合接入速率占活跃应用限流预算的预警比例')
ON DUPLICATE KEY UPDATE description=VALUES(description);

UPDATE t_system_config SET is_active=FALSE
WHERE config_key='sdk.rate-alert-rps' AND is_active=TRUE;

INSERT INTO t_system_config(config_key,config_version,config_value,config_desc,is_active,change_set_id,change_reason)
SELECT 'sdk.rate-warning-utilization','sdk-budget-v1','0.70','SDK 接入预算预警比例',TRUE,UUID(),
       '依据每应用 100次/分钟限流政策，在 70% 预算处预警；并发证据 sdk-c50/c100'
WHERE NOT EXISTS(SELECT 1 FROM t_system_config WHERE config_key='sdk.rate-warning-utilization' AND is_active=TRUE);

UPDATE t_metric_definition
SET definition_text='SDK 回执 RPS；预警线=窗口活跃应用数×每应用分钟限额×70%÷60',metric_version='sdk-rate-v4'
WHERE metric_code='SDK_INGEST_RPS';
