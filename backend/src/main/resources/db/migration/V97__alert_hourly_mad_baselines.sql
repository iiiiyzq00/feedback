CREATE TABLE t_alert_metric_hourly_bucket (
  metric_code VARCHAR(50) NOT NULL,
  dimension_key VARCHAR(100) NOT NULL,
  bucket_start DATETIME NOT NULL,
  sample_count BIGINT NOT NULL,
  completeness DECIMAL(7,6) NOT NULL DEFAULT 1.000000,
  calculated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(metric_code,dimension_key,bucket_start),
  KEY idx_alert_metric_bucket_time(metric_code,bucket_start),
  CONSTRAINT chk_alert_metric_bucket CHECK(sample_count>=0 AND completeness BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='告警小时基线事实；MAD计算权威输入';

INSERT INTO t_alert_metric_hourly_bucket(metric_code,dimension_key,bucket_start,sample_count)
SELECT 'CLUSTER_OCCURRENCE',CAST(l.cluster_id AS CHAR),DATE_FORMAT(s.ingest_time,'%Y-%m-%d %H:00:00'),COUNT(DISTINCT s.id)
FROM t_cluster_link l JOIN t_feedback_source s ON s.id=l.feedback_source_id JOIN t_feedback_content c ON c.source_id=s.id
WHERE l.is_active=1 AND l.relation_role='MAIN' AND c.is_deleted=0 AND c.masking_status='PASS' AND s.ingest_time>=DATE_SUB(NOW(),INTERVAL 29 DAY)
GROUP BY l.cluster_id,DATE_FORMAT(s.ingest_time,'%Y-%m-%d %H:00:00');
INSERT INTO t_alert_metric_hourly_bucket(metric_code,dimension_key,bucket_start,sample_count)
SELECT 'NEGATIVE_SENTIMENT','GLOBAL',DATE_FORMAT(s.ingest_time,'%Y-%m-%d %H:00:00'),COUNT(DISTINCT s.id)
FROM t_feedback_source s JOIN t_feedback_content c ON c.source_id=s.id WHERE c.is_deleted=0 AND c.masking_status='PASS' AND c.user_sentiment='NEGATIVE' AND s.ingest_time>=DATE_SUB(NOW(),INTERVAL 29 DAY)
GROUP BY DATE_FORMAT(s.ingest_time,'%Y-%m-%d %H:00:00');
INSERT INTO t_alert_metric_hourly_bucket(metric_code,dimension_key,bucket_start,sample_count)
SELECT 'SDK_VOLUME','GLOBAL',DATE_FORMAT(occurred_at,'%Y-%m-%d %H:00:00'),COUNT(*) FROM t_sdk_request_telemetry WHERE occurred_at>=DATE_SUB(NOW(),INTERVAL 29 DAY)
GROUP BY DATE_FORMAT(occurred_at,'%Y-%m-%d %H:00:00');

UPDATE t_alert_data_contract SET schema_version='hourly-mad-v1',source_system='t_alert_metric_hourly_bucket',freshness_seconds=3600,completeness_threshold=.950000
WHERE contract_code IN ('hourly_baseline','sdk_volume_baseline','data_completeness') AND contract_status='DRAFT';

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'CLUSTER_GROWTH_ANOMALY','问题簇增长异常','PRODUCT_TREND','产品+研发',290,'shadow-v1','SHADOW','P1',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','CLUSTER_GROWTH_ANOMALY_COUNT','operator','>=','threshold',1,'window','1h'))),JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),120,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='CLUSTER_GROWTH_ANOMALY' AND version_status='SHADOW');
INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'NEGATIVE_SENTIMENT_SURGE','负面情感突增','PRODUCT_TREND','产品运营',300,'shadow-v1','SHADOW','P2',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','NEGATIVE_SENTIMENT_SURGE_INDICATOR','operator','>=','threshold',1,'window','1h'))),JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),120,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='NEGATIVE_SENTIMENT_SURGE' AND version_status='SHADOW');
INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'SDK_VOLUME_ANOMALY','SDK流量异常','DATA_INGEST','数据平台',310,'shadow-v1','SHADOW','P1',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','SDK_VOLUME_ANOMALY_INDICATOR','operator','>=','threshold',1,'window','1h'))),JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),60,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='SDK_VOLUME_ANOMALY' AND version_status='SHADOW');
