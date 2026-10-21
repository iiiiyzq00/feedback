INSERT INTO t_system_config_catalog(config_key,value_type,min_value,max_value,owner_module,description)
VALUES ('sdk.ip-rate-limit','INTEGER',1,10000,'SDK','每个来源 IP 每分钟入口请求上限')
ON DUPLICATE KEY UPDATE value_type='INTEGER',min_value=1,max_value=10000,owner_module='SDK',description=VALUES(description);

INSERT INTO t_system_config(config_key,config_version,config_value,config_desc,is_active,change_set_id,change_reason)
SELECT 'sdk.ip-rate-limit','v1',CAST('60' AS JSON),'每个来源 IP 每分钟入口请求上限',TRUE,UUID(),'V117 SDK 接入健康契约'
WHERE NOT EXISTS (SELECT 1 FROM t_system_config WHERE config_key='sdk.ip-rate-limit' AND is_active=TRUE);
