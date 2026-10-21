CREATE TABLE IF NOT EXISTS t_metric_definition (
  metric_code VARCHAR(80) PRIMARY KEY,
  display_name VARCHAR(100) NOT NULL,
  owner_module VARCHAR(50) NOT NULL,
  entity_type VARCHAR(30) NOT NULL,
  distinct_key VARCHAR(50) NOT NULL,
  time_field VARCHAR(80) DEFAULT NULL,
  default_window VARCHAR(20) NOT NULL,
  definition_text VARCHAR(1000) NOT NULL,
  drilldown_path VARCHAR(255) DEFAULT NULL,
  metric_version VARCHAR(30) NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='指标统一定义与下钻契约';

INSERT INTO t_metric_definition(metric_code,display_name,owner_module,entity_type,distinct_key,time_field,default_window,definition_text,drilldown_path,metric_version) VALUES
('INGEST_RECEIVED','正式接收','INGESTION','FEEDBACK','source_id','t_feedback_source.ingest_time','TODAY','已建立正式 source/content 且未删除的反馈','/monitor/operations/received','record-funnel-v4'),
('PRIVACY_PASS','隐私处理完成','INGESTION','FEEDBACK','source_id','t_feedback_source.ingest_time','TODAY','正式接收中 masking_status=PASS','/monitor/operations/passed','record-funnel-v4'),
('AI_PROCESSED','智能分类完成','INGESTION','FEEDBACK','source_id','t_feedback_source.ingest_time','TODAY','隐私通过且 ai_processed_at 非空','/monitor/operations/ai','record-funnel-v4'),
('CLUSTER_DECIDED','归簇决策完成','INGESTION','FEEDBACK','source_id','t_feedback_source.ingest_time','TODAY','存在活跃 MAIN 归簇关系','/monitor/operations/clusters?decisionType=MAIN','record-funnel-v4'),
('AI_REVIEW_BACKLOG','AI待复核','AI','FEEDBACK','source_id',NULL,'CURRENT_BACKLOG','review_status=PENDING 的可见安全反馈','/monitor/ai-review','ai-review-backlog-v2'),
('CLUSTER_REVIEW_BACKLOG','归簇边界审核','CLUSTER','FEEDBACK','source_id',NULL,'CURRENT_BACKLOG','活跃 CANDIDATE 且 review_status=PENDING','/monitor/cluster-audit','cluster-review-backlog-v2'),
('SDK_INGEST_FAILURE_BACKLOG','SDK重试/失败','INGESTION','SDK_RECEIPT','receipt_id',NULL,'CURRENT_BACKLOG','SDK 回执状态为 RETRY_PENDING 或 FAILED','/monitor/operations/sdk','sdk-ingest-backlog-v2'),
('SDK_INGEST_RPS','SDK实时速率','INGESTION','SDK_RECEIPT','receipt_id','t_sdk_ingest.received_at','ROLLING_1H','SDK 后端接收回执按分箱秒数归一化的 RPS',NULL,'sdk-rate-v3')
ON DUPLICATE KEY UPDATE
  display_name=VALUES(display_name),
  owner_module=VALUES(owner_module),
  entity_type=VALUES(entity_type),
  distinct_key=VALUES(distinct_key),
  time_field=VALUES(time_field),
  default_window=VALUES(default_window),
  definition_text=VALUES(definition_text),
  drilldown_path=VALUES(drilldown_path),
  metric_version=VALUES(metric_version),
  is_active=TRUE;

INSERT INTO t_system_config_catalog(config_key,value_type,min_value,max_value,owner_module,description)
VALUES ('sdk.rate-alert-rps','DECIMAL',0.01,100000,'INGESTION','SDK 接入 RPS 临时告警线，正式值必须由容量测试发布')
ON DUPLICATE KEY UPDATE description=VALUES(description);

INSERT INTO t_system_config(config_key,config_version,config_value,config_desc,is_active,change_set_id,change_reason)
SELECT 'sdk.rate-alert-rps','sdk-capacity-prov-v1','2.0','SDK 接入 RPS 临时告警阈值',TRUE,UUID(),'由旧 120请求/分钟统一换算，待压测替换'
WHERE NOT EXISTS(SELECT 1 FROM t_system_config WHERE config_key='sdk.rate-alert-rps' AND is_active=TRUE);
