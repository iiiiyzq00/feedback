-- First implementation batch uses only facts already persisted by the main
-- ingestion pipeline. Contracts remain DRAFT and catalog entries remain
-- CONTRACT_REQUIRED until independent validation and replay acceptance.
UPDATE t_alert_data_contract SET schema_version='feedback-source-v1',source_system='t_feedback_source',freshness_seconds=300,completeness_threshold=.990000
WHERE contract_code IN ('source_channel','last_ingest_at') AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='import-batch-v1',source_system='t_import_batch',freshness_seconds=300,completeness_threshold=.990000
WHERE contract_code IN ('import_batch_event','import_batch_counts') AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='import-row-quality-v1',source_system='t_import_row',freshness_seconds=300,completeness_threshold=.990000
WHERE contract_code='mapping_error_code' AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='masking-state-v1',source_system='t_feedback_content',freshness_seconds=300,completeness_threshold=.990000
WHERE contract_code IN ('masking_status','masking_attempt','masking_queue_state') AND contract_status='DRAFT';
UPDATE t_alert_data_contract SET schema_version='quality-snapshot-v1',source_system='t_import_quality_snapshot',freshness_seconds=3600,completeness_threshold=.990000
WHERE contract_code='data_quality_score' AND contract_status='DRAFT';

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'CHANNEL_DATA_STALE','渠道数据断流','DATA_INGEST','数据平台+渠道负责人',110,'shadow-v1','SHADOW','P1',
 JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','CHANNEL_MAX_STALE_MINUTES','operator','>=','threshold',15,'window','5m'))),JSON_OBJECT('threshold',5),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0
WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='CHANNEL_DATA_STALE' AND version_status='SHADOW');

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'IMPORT_BATCH_FAILED','导入批次失败','DATA_INGEST','数据平台+渠道负责人',120,'shadow-v1','SHADOW','P2',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','IMPORT_FAILED_BATCH_COUNT','operator','>=','threshold',1,'window','15m'))),JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='IMPORT_BATCH_FAILED' AND version_status='SHADOW');

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'IMPORT_FAILURE_RATE_HIGH','导入失败率过高','DATA_INGEST','数据平台+渠道负责人',130,'shadow-v1','SHADOW','P2',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','IMPORT_ROW_COUNT','operator','>=','threshold',100,'window','15m'),JSON_OBJECT('metric','IMPORT_FAILURE_RATE','operator','>=','threshold',10,'window','15m'))),JSON_OBJECT('threshold',5),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='IMPORT_FAILURE_RATE_HIGH' AND version_status='SHADOW');

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'MAPPING_ERROR_RATE_HIGH','映射错误率过高','DATA_QUALITY','数据平台+渠道负责人',140,'shadow-v1','SHADOW','P2',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','MAPPING_ROW_COUNT','operator','>=','threshold',100,'window','15m'),JSON_OBJECT('metric','MAPPING_ERROR_RATE','operator','>=','threshold',10,'window','15m'))),JSON_OBJECT('threshold',5),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='MAPPING_ERROR_RATE_HIGH' AND version_status='SHADOW');

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'MASKING_FAILURE_RATE_HIGH','脱敏失败率过高','MASKING','隐私+数据平台',150,'shadow-v1','SHADOW','P1',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','MASKING_ATTEMPT_COUNT','operator','>=','threshold',20,'window','15m'),JSON_OBJECT('metric','MASKING_FAILURE_RATE','operator','>=','threshold',1,'window','15m'))),JSON_OBJECT('threshold',.5),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='MASKING_FAILURE_RATE_HIGH' AND version_status='SHADOW');

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'MASKING_PIPELINE_BACKLOG','脱敏管道积压','MASKING','隐私+数据平台',160,'shadow-v1','SHADOW','P1',JSON_OBJECT('combinator','ANY','conditions',JSON_ARRAY(JSON_OBJECT('metric','MASKING_BACKLOG_COUNT','operator','>=','threshold',500,'window','15m'),JSON_OBJECT('metric','MASKING_OLDEST_PENDING_MINUTES','operator','>=','threshold',10,'window','15m'))),JSON_OBJECT('threshold',3),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),15,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='MASKING_PIPELINE_BACKLOG' AND version_status='SHADOW');

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'DATA_QUALITY_DROP','数据质量下降','DATA_QUALITY','数据平台',170,'shadow-v1','SHADOW','P2',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','QUALITY_SAMPLE_COUNT','operator','>=','threshold',100,'window','1h'),JSON_OBJECT('metric','QUALITY_SCORE_PERCENT','operator','<','threshold',85,'window','1h'))),JSON_OBJECT('threshold',90),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),60,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='DATA_QUALITY_DROP' AND version_status='SHADOW');
