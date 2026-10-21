CREATE TABLE t_alert_rule_catalog (
  rule_code VARCHAR(80) PRIMARY KEY,
  rule_name VARCHAR(200) NOT NULL,
  category VARCHAR(30) NOT NULL,
  default_severity VARCHAR(5) NOT NULL,
  owner_team VARCHAR(100) NOT NULL,
  evaluation_mode VARCHAR(20) NOT NULL,
  implementation_status VARCHAR(30) NOT NULL,
  governance_status VARCHAR(20) NOT NULL,
  parameter_template JSON NOT NULL,
  recovery_policy VARCHAR(500) NOT NULL,
  data_dependencies JSON NOT NULL,
  p0_non_silenceable BOOLEAN NOT NULL DEFAULT FALSE,
  source_reference VARCHAR(200) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_alert_catalog_severity CHECK (default_severity IN ('P0','P1','P2','P3')),
  CONSTRAINT chk_alert_catalog_mode CHECK (evaluation_mode IN ('EVENT','SCHEDULED','PRE_RELEASE','EXTERNAL')),
  CONSTRAINT chk_alert_catalog_implementation CHECK (implementation_status IN ('IMPLEMENTED','CONTRACT_REQUIRED','EXTERNAL')),
  CONSTRAINT chk_alert_catalog_governance CHECK (governance_status IN ('DRAFT','APPROVED','DEPRECATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='受控业务告警规则目录；目录获批不等于规则已启用';

INSERT INTO t_alert_rule_catalog
(rule_code,rule_name,category,default_severity,owner_team,evaluation_mode,implementation_status,governance_status,parameter_template,recovery_policy,data_dependencies,p0_non_silenceable,source_reference) VALUES
('CONTACT_ESCALATION','同一用户诉求升级','USER_REQUEST','P1','客服+产品运营','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','24h','minOccurrences',3,'minChannels',2),'24h无新增后恢复',JSON_ARRAY('contact_fingerprint','cluster_id','source_channel'),0,'方案3.8.4'),
('URGENT_FEEDBACK_PROCESSING_SLA','紧急反馈处理SLA','USER_REQUEST','P1','客服+产品运营','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('evaluation','5m','maxPendingMinutes',15),'进入可处理状态后恢复',JSON_ARRAY('urgency','processing_status','received_at'),0,'方案3.8.4'),
('FAILED_FEEDBACK_RECOVERY_SLA','失败反馈恢复SLA','USER_REQUEST','P1','数据平台+客服','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('evaluation','5m','urgentMinutes',15,'normalMinutes',120),'恢复或人工接管后恢复',JSON_ARRAY('urgency','ingest_status','manual_takeover'),0,'方案3.8.4'),
('DEVICE_PERSISTENT_FAILURE','设备持续故障','USER_REQUEST','P1','客服+产品研发','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window24h',3,'window7d',5),'24h无新增后恢复',JSON_ARRAY('device_fingerprint','cluster_id'),0,'方案3.8.4'),
('DEVICE_SAFETY_SUSPECTED','设备安全疑似','SAFETY','P1','硬件质量+安全','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('evidence','SUSPECTED'),'人工确认或24h无新增',JSON_ARRAY('safety_classification','evidence_level'),0,'方案3.8.4'),
('DEVICE_SAFETY_STRONG_EVIDENCE','设备安全强证据','SAFETY','P0','硬件质量+安全','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('signals',JSON_ARRAY('SMOKE','FIRE','EXPLOSION','ELECTRIC_LEAK')),'必须人工闭环',JSON_ARRAY('safety_classification','evidence_snapshot'),1,'方案3.8.4'),
('SENSITIVE_DATA_RESIDUAL_BLOCKED','敏感数据残留被阻断','SAFETY','P1','隐私+数据平台','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('risk','HIGH'),'安全销毁且规则修复后恢复',JSON_ARRAY('masking_scan_event','risk_level'),0,'方案3.8.4'),
('SENSITIVE_DATA_EXPOSURE_CONFIRMED','敏感数据暴露确认','SAFETY','P0','隐私+安全','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('confirmed',true),'必须人工Incident闭环',JSON_ARRAY('exposure_audit_event'),1,'方案3.8.4'),
('UNAUTHORIZED_ACCESS_DETECTED','越权访问确认','SAFETY','P0','安全','EVENT','EXTERNAL','APPROVED',JSON_OBJECT('confirmed',true),'必须人工Incident闭环',JSON_ARRAY('SIEM','audit_event'),1,'方案3.8.4'),
('KEY_MATERIAL_EXPOSURE','密钥凭据暴露','SAFETY','P0','安全+平台','EVENT','EXTERNAL','APPROVED',JSON_OBJECT('confirmed',true),'完成轮换并人工闭环',JSON_ARRAY('secret_scanner','SIEM'),1,'方案3.8.4'),
('CLUSTER_GROWTH_ANOMALY','问题簇增长异常','PRODUCT_TREND','P1','产品+研发','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','1h','minCount',10,'madMultiplier',6,'baselineDays',28),'连续2窗口低于70%恢复',JSON_ARRAY('cluster_id','hourly_baseline','data_completeness'),0,'方案3.8.4'),
('VERSION_REGRESSION','版本回归','PRODUCT_TREND','P1','产品+研发','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','30m','minCount',20,'minRate',0.01,'controlRatio',2.0),'连续2窗口低于对照1.4倍恢复',JSON_ARRAY('app_version','cluster_id','control_version'),0,'方案3.8.4'),
('RESOLVED_ISSUE_REOCCURRED','已解决问题复发','PRODUCT_TREND','P1','产品+研发','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','24h','minCount',3,'minSubjects',2),'24h无新增恢复',JSON_ARRAY('cluster_resolution','subject_fingerprint'),0,'方案3.8.4'),
('NEGATIVE_SENTIMENT_SURGE','负面情感突增','PRODUCT_TREND','P2','产品运营','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','1h','minCount',20,'madMultiplier',6,'baselineDays',28),'连续2窗口恢复',JSON_ARRAY('user_sentiment','hourly_baseline'),0,'方案3.8.4'),
('LONG_UNRESOLVED_CLUSTER','长期未解决问题簇','PRODUCT_TREND','P3','产品+研发','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('unresolvedDays',30,'recentDays',7),'解决或7d无新增恢复',JSON_ARRAY('cluster_lifecycle','last_occurrence'),0,'方案3.8.4'),
('CONTENT_ERROR_SPIKE','内容错误突增','PRODUCT_TREND','P1','内容团队','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','4h','minCount',3,'minSubjects',2),'连续8h低于恢复阈值',JSON_ARRAY('issue_category','subject_fingerprint'),0,'方案3.8.4'),
('RED_BLACK_CLUSTER_FLOOD','红黑榜同簇聚集','PRODUCT_TREND','P2','产品运营','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','1h','minCount',5,'minSubjects',3),'连续2窗口恢复',JSON_ARRAY('source_channel','cluster_id','subject_fingerprint'),0,'方案3.8.4'),
('CHANNEL_DATA_STALE','渠道数据断流','DATA_INGEST','P1','数据平台+渠道负责人','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('evaluation','5m','sdkMinutes',15,'channelSlaRequired',true),'新数据到达恢复',JSON_ARRAY('source_channel','channel_sla','last_ingest_at'),0,'方案3.8.4'),
('SDK_VOLUME_ANOMALY','SDK流量异常','DATA_INGEST','P1','数据平台','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','30m','minCount',20,'baselineDays',28,'madMultiplier',3),'连续2窗口恢复',JSON_ARRAY('sdk_volume_baseline','data_completeness'),0,'方案3.8.4'),
('IMPORT_BATCH_FAILED','导入批次失败','DATA_INGEST','P2','数据平台+渠道负责人','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('status','FAILED'),'重跑成功或人工关闭',JSON_ARRAY('import_batch_event'),0,'方案3.8.4'),
('IMPORT_FAILURE_RATE_HIGH','导入失败率过高','DATA_INGEST','P2','数据平台+渠道负责人','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('minRows',100,'failureRate',0.10),'下一有效批次低于5%恢复',JSON_ARRAY('import_batch_counts'),0,'方案3.8.4'),
('ATTACHMENT_FAILURE_RATE_HIGH','附件失败率过高','DATA_INGEST','P2','数据平台','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','1h','minCount',20,'failureRate',0.10),'连续2窗口低于5%恢复',JSON_ARRAY('attachment_final_status'),0,'方案3.8.4'),
('INGEST_PROCESSING_FAILED','接入信封处理失败','DATA_INGEST','P1','数据平台','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('retryExhausted',true,'urgentSeverity','P1','normalSeverity','P2'),'补偿成功或人工接管',JSON_ARRAY('ingest_retry_event','urgency'),0,'方案3.8.4'),
('MASKING_FAILURE_RATE_HIGH','脱敏失败率过高','MASKING','P1','隐私+数据平台','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','15m','minCount',20,'failureRate',0.01),'连续2窗口低于0.5%恢复',JSON_ARRAY('masking_status','masking_attempt'),0,'方案3.8.4'),
('MASKING_PIPELINE_BACKLOG','脱敏管道积压','MASKING','P1','隐私+数据平台','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('oldestMinutes',10,'backlog',500),'最老低于3min且积压低于100',JSON_ARRAY('masking_queue_state'),0,'方案3.8.4'),
('MASKING_REVIEW_SLA_BREACH','脱敏审核SLA超时','MASKING','P2','隐私运营','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('evaluation','15m','slaConfigRequired',true),'超时项清零',JSON_ARRAY('masking_review_queue','sla_config'),0,'方案3.8.4'),
('MASKING_RULE_QUALITY_DEGRADED','脱敏规则质量下降','MASKING','P1','隐私+数据平台','PRE_RELEASE','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('minF1',0.90,'minHighRiskRecall',0.98),'新版本通过评估后恢复',JSON_ARRAY('masking_evaluation'),0,'方案3.8.4'),
('DATA_QUALITY_DROP','数据质量下降','DATA_QUALITY','P2','数据平台','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','1h','minCount',100,'minPassRate',0.85),'连续2窗口达到90%恢复',JSON_ARRAY('data_quality_score'),0,'方案3.8.4'),
('MAPPING_ERROR_RATE_HIGH','映射错误率过高','DATA_QUALITY','P2','数据平台+渠道负责人','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('minRows',100,'errorRate',0.10),'下一有效批次低于5%恢复',JSON_ARRAY('mapping_error_code','import_batch_counts'),0,'方案3.8.4'),
('INCORRECT_DEDUP_DETECTED','错误去重确认','DATA_QUALITY','P1','数据平台','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('manualConfirmation',true),'数据修复并人工确认',JSON_ARRAY('dedup_review_event','urgency'),0,'方案3.8.4'),
('UNHANDLED_IDEMPOTENCY_CONFLICT','未处理幂等冲突','DATA_QUALITY','P2','数据平台','EVENT','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('unhandledCount',1),'冲突全部处置后恢复',JSON_ARRAY('idempotency_conflict_event'),0,'方案3.8.4'),
('CLUSTER_REVIEW_SLA_BREACH','问题簇审核SLA超时','DATA_QUALITY','P2','产品运营','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('evaluation','15m','p1Hours',4,'p2BusinessDays',1,'p3BusinessDays',3),'超时项清零',JSON_ARRAY('cluster_review_queue','business_calendar'),0,'方案3.8.4'),
('CLUSTER_MANUAL_OVERTURN_HIGH','问题簇人工推翻率过高','DATA_QUALITY','P2','AI平台+产品运营','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('window','7d','minReviews',30,'overturnRate',0.20),'连续2次低于10%恢复',JSON_ARRAY('cluster_review_decision'),0,'方案3.8.4'),
('RULE_INPUT_DATA_INCOMPLETE','规则输入数据不完整','DATA_QUALITY','P1','数据平台','SCHEDULED','CONTRACT_REQUIRED','APPROVED',JSON_OBJECT('evaluation','5m','minCompleteness',0.95,'recoveryCompleteness',0.99),'连续2窗口达到99%恢复',JSON_ARRAY('rule_input_completeness'),0,'方案3.8.4');

-- Day 7 数据只用于验收，迁移到正式治理基线时一律停用，保留历史证据。
UPDATE t_alert_rule
SET is_enabled=0
WHERE rule_code LIKE 'DAY7\_%' ESCAPE '\\';
