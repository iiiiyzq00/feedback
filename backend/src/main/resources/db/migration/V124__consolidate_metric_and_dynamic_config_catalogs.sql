-- Read-only catalog consolidation into the versioned system configuration store.

INSERT INTO t_system_config(
  config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,owner_team,config_desc,is_active,created_at,updated_at)
SELECT CONCAT('METRIC_DEFINITION:',metric_code),'METRIC_DEFINITION','v1',
  JSON_OBJECT('metricCode',metric_code,'displayName',display_name,'ownerModule',owner_module,
    'entityType',entity_type,'distinctKey',distinct_key,'timeField',time_field,
    'defaultWindow',default_window,'definition',definition_text,'drilldownPath',drilldown_path,
    'metricVersion',metric_version),
  'metric-definition-v1','ACTIVE','APPROVED',owner_module,definition_text,is_active,created_at,updated_at
FROM t_metric_definition
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),owner_team=VALUES(owner_team),
  config_desc=VALUES(config_desc),is_active=VALUES(is_active),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(
  config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,owner_team,config_desc,is_active)
SELECT CONCAT('DYNAMIC_CONFIG_CATALOG:',config_key),'DYNAMIC_CONFIG_CATALOG','v1',
  JSON_OBJECT('configKey',config_key,'valueType',value_type,'minValue',min_value,'maxValue',max_value,
    'hotReload',hot_reload,'secretValue',secret_value,'ownerModule',owner_module,'description',description),
  'dynamic-catalog-v1','ACTIVE','APPROVED',owner_module,description,1
FROM t_system_config_catalog
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),owner_team=VALUES(owner_team),
  config_desc=VALUES(config_desc),is_active=1;
