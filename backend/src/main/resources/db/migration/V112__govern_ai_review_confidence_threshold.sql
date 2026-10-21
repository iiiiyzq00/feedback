INSERT INTO t_system_config_catalog(config_key,value_type,min_value,max_value,hot_reload,owner_module,description)
VALUES('ai.review-confidence-threshold','DECIMAL',0,1,TRUE,'AI','AI 分类自动采用、待复核队列与归簇边界共用的置信度门槛')
ON DUPLICATE KEY UPDATE value_type='DECIMAL',min_value=0,max_value=1,hot_reload=TRUE,
  owner_module='AI',description=VALUES(description);

INSERT INTO t_system_config(config_key,config_version,config_value,config_desc,is_active,change_set_id,change_reason)
SELECT 'ai.review-confidence-threshold','v1',CAST('0.85' AS JSON),'AI 分类待复核置信度门槛',1,UUID(),'V112收敛AI门禁口径'
WHERE NOT EXISTS(SELECT 1 FROM t_system_config WHERE config_key='ai.review-confidence-threshold' AND is_active=TRUE);
