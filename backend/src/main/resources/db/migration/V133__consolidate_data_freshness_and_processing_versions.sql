-- Consolidate data-governance state, history facts and processing versions.
ALTER TABLE t_alert_occurrence
  ADD COLUMN latest_event_time DATETIME(3) DEFAULT NULL AFTER error_summary,
  ADD COLUMN lag_seconds BIGINT DEFAULT NULL AFTER latest_event_time,
  ADD COLUMN threshold_seconds BIGINT DEFAULT NULL AFTER lag_seconds,
  ADD COLUMN fact_status VARCHAR(30) DEFAULT NULL AFTER threshold_seconds,
  ADD KEY idx_occurrence_freshness_channel (occurrence_type,dimension_key,evaluation_time,fact_status);

INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,latest_event_time,lag_seconds,threshold_seconds,fact_status,metric_value,
 evaluation_snapshot,evaluation_time,created_at)
SELECT NULL,'DATA_FRESHNESS_CHECK','CHANNEL_INGEST_LAG_SECONDS',channel,
 't_data_freshness_sla_history',id,latest_ingest_time,lag_seconds,threshold_seconds,sla_status,lag_seconds,
 JSON_OBJECT('channel',channel,'latestIngestTime',latest_ingest_time,'lagSeconds',lag_seconds,
  'thresholdSeconds',threshold_seconds,'status',sla_status),checked_at,checked_at
FROM t_data_freshness_sla_history
ON DUPLICATE KEY UPDATE latest_event_time=VALUES(latest_event_time),lag_seconds=VALUES(lag_seconds),
 threshold_seconds=VALUES(threshold_seconds),fact_status=VALUES(fact_status),metric_value=VALUES(metric_value),
 evaluation_snapshot=VALUES(evaluation_snapshot),evaluation_time=VALUES(evaluation_time);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,record_status,effective_from,is_active,updated_at)
SELECT CONCAT('DATA_FRESHNESS:',channel),'DATA_FRESHNESS_SLA','v1',JSON_OBJECT('channel',channel,
 'latestIngestTime',latest_ingest_time,'lagSeconds',lag_seconds,'thresholdSeconds',threshold_seconds,
 'status',sla_status),'freshness-sla-v1','ACTIVE','APPROVED',channel,sla_status,checked_at,1,checked_at
FROM t_data_freshness_sla
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),record_status=VALUES(record_status),
 effective_from=VALUES(effective_from),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,config_subtype,effective_from,effective_to,config_desc,
 is_active,created_by,created_at,updated_at)
SELECT CONCAT('PROCESSING_VERSION:',id),'DATA_PROCESSING_VERSION',version_code,JSON_OBJECT('legacyId',id,
 'versionType',version_type,'scopeCode',scope_code,'versionCode',version_code,'ruleConfig',rule_config,
 'contentSha256',content_sha256),'processing-ver-v1',lifecycle_status,
 IF(lifecycle_status='ACTIVE','APPROVED','PENDING'),scope_code,version_type,effective_at,retired_at,
 content_sha256,lifecycle_status='ACTIVE',created_by,created_at,created_at
FROM t_data_processing_version
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),lifecycle_status=VALUES(lifecycle_status),
 approval_status=VALUES(approval_status),scope_key=VALUES(scope_key),config_subtype=VALUES(config_subtype),
 effective_from=VALUES(effective_from),effective_to=VALUES(effective_to),is_active=VALUES(is_active);

CREATE TRIGGER trg_freshness_history_compact_insert AFTER INSERT ON t_data_freshness_sla_history FOR EACH ROW
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,latest_event_time,lag_seconds,threshold_seconds,fact_status,metric_value,
 evaluation_snapshot,evaluation_time,created_at)
VALUES(NULL,'DATA_FRESHNESS_CHECK','CHANNEL_INGEST_LAG_SECONDS',NEW.channel,
 't_data_freshness_sla_history',NEW.id,NEW.latest_ingest_time,NEW.lag_seconds,NEW.threshold_seconds,
 NEW.sla_status,NEW.lag_seconds,JSON_OBJECT('channel',NEW.channel,'latestIngestTime',NEW.latest_ingest_time,
 'lagSeconds',NEW.lag_seconds,'thresholdSeconds',NEW.threshold_seconds,'status',NEW.sla_status),
 NEW.checked_at,NEW.checked_at);
CREATE TRIGGER trg_freshness_history_compact_delete AFTER DELETE ON t_data_freshness_sla_history FOR EACH ROW
DELETE FROM t_alert_occurrence WHERE legacy_source_table='t_data_freshness_sla_history' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_freshness_current_config_insert AFTER INSERT ON t_data_freshness_sla FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,record_status,effective_from,is_active,updated_at)
VALUES(CONCAT('DATA_FRESHNESS:',NEW.channel),'DATA_FRESHNESS_SLA','v1',JSON_OBJECT('channel',NEW.channel,
 'latestIngestTime',NEW.latest_ingest_time,'lagSeconds',NEW.lag_seconds,'thresholdSeconds',NEW.threshold_seconds,
 'status',NEW.sla_status),'freshness-sla-v1','ACTIVE','APPROVED',NEW.channel,NEW.sla_status,NEW.checked_at,1,NEW.checked_at);
CREATE TRIGGER trg_freshness_current_config_update AFTER UPDATE ON t_data_freshness_sla FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('channel',NEW.channel,'latestIngestTime',NEW.latest_ingest_time,
 'lagSeconds',NEW.lag_seconds,'thresholdSeconds',NEW.threshold_seconds,'status',NEW.sla_status),
 scope_key=NEW.channel,record_status=NEW.sla_status,effective_from=NEW.checked_at,updated_at=NEW.checked_at
WHERE config_key=CONCAT('DATA_FRESHNESS:',OLD.channel) AND config_version='v1';
CREATE TRIGGER trg_freshness_current_config_delete AFTER DELETE ON t_data_freshness_sla FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('DATA_FRESHNESS:',OLD.channel) AND config_version='v1';

CREATE TRIGGER trg_processing_version_config_insert AFTER INSERT ON t_data_processing_version FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,config_subtype,effective_from,effective_to,config_desc,
 is_active,created_by,created_at,updated_at)
VALUES(CONCAT('PROCESSING_VERSION:',NEW.id),'DATA_PROCESSING_VERSION',NEW.version_code,JSON_OBJECT('legacyId',NEW.id,
 'versionType',NEW.version_type,'scopeCode',NEW.scope_code,'versionCode',NEW.version_code,
 'ruleConfig',NEW.rule_config,'contentSha256',NEW.content_sha256),'processing-ver-v1',NEW.lifecycle_status,
 IF(NEW.lifecycle_status='ACTIVE','APPROVED','PENDING'),NEW.scope_code,NEW.version_type,NEW.effective_at,
 NEW.retired_at,NEW.content_sha256,NEW.lifecycle_status='ACTIVE',NEW.created_by,NEW.created_at,NEW.created_at);
CREATE TRIGGER trg_processing_version_config_update AFTER UPDATE ON t_data_processing_version FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('legacyId',NEW.id,'versionType',NEW.version_type,
 'scopeCode',NEW.scope_code,'versionCode',NEW.version_code,'ruleConfig',NEW.rule_config,
 'contentSha256',NEW.content_sha256),lifecycle_status=NEW.lifecycle_status,
 approval_status=IF(NEW.lifecycle_status='ACTIVE','APPROVED','PENDING'),scope_key=NEW.scope_code,
 config_subtype=NEW.version_type,effective_from=NEW.effective_at,effective_to=NEW.retired_at,
 config_desc=NEW.content_sha256,is_active=NEW.lifecycle_status='ACTIVE'
WHERE config_key=CONCAT('PROCESSING_VERSION:',OLD.id) AND config_version=OLD.version_code;
CREATE TRIGGER trg_processing_version_config_delete AFTER DELETE ON t_data_processing_version FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('PROCESSING_VERSION:',OLD.id) AND config_version=OLD.version_code;
