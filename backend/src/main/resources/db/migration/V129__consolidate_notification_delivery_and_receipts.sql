-- Consolidate notification delivery facts and receipts while retaining legacy rollback shadows.
ALTER TABLE t_alert_occurrence
  ADD COLUMN notification_id BIGINT DEFAULT NULL AFTER data_contract_version,
  ADD COLUMN attempt_no INT DEFAULT NULL AFTER notification_id,
  ADD COLUMN delivery_channel VARCHAR(30) DEFAULT NULL AFTER attempt_no,
  ADD COLUMN delivery_status VARCHAR(30) DEFAULT NULL AFTER delivery_channel,
  ADD COLUMN duration_ms BIGINT DEFAULT NULL AFTER delivery_status,
  ADD COLUMN http_status SMALLINT DEFAULT NULL AFTER duration_ms,
  ADD COLUMN provider_code VARCHAR(100) DEFAULT NULL AFTER http_status,
  ADD COLUMN provider_message_id VARCHAR(255) DEFAULT NULL AFTER provider_code,
  ADD COLUMN error_type VARCHAR(50) DEFAULT NULL AFTER provider_message_id,
  ADD COLUMN error_summary VARCHAR(500) DEFAULT NULL AFTER error_type,
  ADD KEY idx_occurrence_delivery_notification (occurrence_type,notification_id,attempt_no),
  ADD KEY idx_occurrence_delivery_window (occurrence_type,evaluation_time,delivery_status,delivery_channel);

INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
  legacy_source_id,notification_id,attempt_no,delivery_channel,delivery_status,window_start,window_end,
  duration_ms,http_status,provider_code,provider_message_id,error_type,error_summary,evaluation_snapshot,evaluation_time,created_at)
SELECT n.alert_instance_id,'DELIVERY_ATTEMPT','NOTIFICATION_DELIVERY',a.notify_channel,'t_alert_delivery_attempt',
  a.id,a.notification_id,a.attempt_no,a.notify_channel,a.attempt_status,a.started_at,a.completed_at,a.duration_ms,
  a.http_status,a.provider_code,a.provider_message_id,a.error_type,a.error_summary,
  JSON_OBJECT('notificationId',a.notification_id,'attemptNo',a.attempt_no),a.completed_at,a.created_at
FROM t_alert_delivery_attempt a JOIN t_alert_notification n ON n.id=a.notification_id
ON DUPLICATE KEY UPDATE instance_id=VALUES(instance_id),attempt_no=VALUES(attempt_no),delivery_channel=VALUES(delivery_channel),
  delivery_status=VALUES(delivery_status),window_start=VALUES(window_start),window_end=VALUES(window_end),
  duration_ms=VALUES(duration_ms),http_status=VALUES(http_status),provider_code=VALUES(provider_code),
  provider_message_id=VALUES(provider_message_id),error_type=VALUES(error_type),error_summary=VALUES(error_summary),
  evaluation_time=VALUES(evaluation_time),created_at=VALUES(created_at);

CREATE TRIGGER trg_delivery_attempt_compact_insert AFTER INSERT ON t_alert_delivery_attempt FOR EACH ROW
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
  legacy_source_id,notification_id,attempt_no,delivery_channel,delivery_status,window_start,window_end,
  duration_ms,http_status,provider_code,provider_message_id,error_type,error_summary,evaluation_snapshot,evaluation_time,created_at)
SELECT n.alert_instance_id,'DELIVERY_ATTEMPT','NOTIFICATION_DELIVERY',NEW.notify_channel,'t_alert_delivery_attempt',
  NEW.id,NEW.notification_id,NEW.attempt_no,NEW.notify_channel,NEW.attempt_status,NEW.started_at,NEW.completed_at,
  NEW.duration_ms,NEW.http_status,NEW.provider_code,NEW.provider_message_id,NEW.error_type,NEW.error_summary,
  JSON_OBJECT('notificationId',NEW.notification_id,'attemptNo',NEW.attempt_no),NEW.completed_at,NEW.created_at
FROM t_alert_notification n WHERE n.id=NEW.notification_id;

CREATE TRIGGER trg_delivery_attempt_compact_update AFTER UPDATE ON t_alert_delivery_attempt FOR EACH ROW
UPDATE t_alert_occurrence SET attempt_no=NEW.attempt_no,delivery_channel=NEW.notify_channel,
  delivery_status=NEW.attempt_status,window_start=NEW.started_at,window_end=NEW.completed_at,
  duration_ms=NEW.duration_ms,http_status=NEW.http_status,provider_code=NEW.provider_code,
  provider_message_id=NEW.provider_message_id,error_type=NEW.error_type,error_summary=NEW.error_summary,
  evaluation_time=NEW.completed_at,created_at=NEW.created_at
WHERE legacy_source_table='t_alert_delivery_attempt' AND legacy_source_id=OLD.id;
CREATE TRIGGER trg_delivery_attempt_compact_delete AFTER DELETE ON t_alert_delivery_attempt FOR EACH ROW
DELETE FROM t_alert_occurrence WHERE legacy_source_table='t_alert_delivery_attempt' AND legacy_source_id=OLD.id;

ALTER TABLE t_system_audit_log
  ADD COLUMN idempotency_key VARCHAR(255) DEFAULT NULL AFTER legacy_source_id,
  ADD UNIQUE KEY uk_audit_operation_idempotency (operation,idempotency_key),
  ADD KEY idx_audit_user_target (operation,user_id,target_id,event_time,id);

INSERT INTO t_system_audit_log(user_id,operation,audit_class,outcome,target_type,target_id,operation_detail,
  detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at)
SELECT e.created_by,'ALERT_DELIVERY_EVIDENCE','BUSINESS','SUCCESS','ALERT_NOTIFICATION',CAST(e.notification_id AS CHAR),
  JSON_OBJECT('type',e.evidence_type,'reference',e.evidence_reference,'sha256',e.evidence_sha256,'note',e.note),
  'delivery-evidence-v1',e.created_at,'t_alert_delivery_evidence',e.id,CONCAT('evidence:',e.id),e.created_at
FROM t_alert_delivery_evidence e ON DUPLICATE KEY UPDATE operation_detail=VALUES(operation_detail),event_time=VALUES(event_time);

INSERT INTO t_system_audit_log(operation,audit_class,outcome,target_type,target_id,operation_detail,
  detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at)
SELECT 'NOTIFICATION_PROVIDER_RECEIPT','SYSTEM','SUCCESS','ALERT_NOTIFICATION',CAST(r.notification_id AS CHAR),
  JSON_OBJECT('callbackId',r.callback_id,'providerCode',r.provider_code,'channel',r.notify_channel,
    'providerMessageId',r.provider_message_id,'notificationId',r.notification_id,'eventType',r.event_type,
    'dtmfDigit',r.dtmf_digit,'payloadSha256',r.payload_sha256,'receivedAt',r.received_at),
  'provider-receipt-v1',r.event_time,'t_notification_provider_receipt',r.id,
  CONCAT(r.provider_code,':',r.callback_id),r.received_at
FROM t_notification_provider_receipt r ON DUPLICATE KEY UPDATE operation_detail=VALUES(operation_detail),event_time=VALUES(event_time);

INSERT INTO t_system_audit_log(user_id,operation,audit_class,outcome,target_type,target_id,operation_detail,
  detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at)
SELECT r.user_id,'IN_APP_NOTIFICATION_READ','BUSINESS','SUCCESS','ALERT_NOTIFICATION',CAST(r.notification_id AS CHAR),
  JSON_OBJECT('readAt',r.read_at),'in-app-read-v1',r.read_at,'t_in_app_notification_receipt',r.id,
  CONCAT(r.notification_id,':',r.user_id),r.created_at
FROM t_in_app_notification_receipt r ON DUPLICATE KEY UPDATE operation_detail=VALUES(operation_detail),event_time=VALUES(event_time);

CREATE TRIGGER trg_delivery_evidence_compact_insert AFTER INSERT ON t_alert_delivery_evidence FOR EACH ROW
INSERT INTO t_system_audit_log(user_id,operation,audit_class,outcome,target_type,target_id,operation_detail,
 detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at)
VALUES(NEW.created_by,'ALERT_DELIVERY_EVIDENCE','BUSINESS','SUCCESS','ALERT_NOTIFICATION',CAST(NEW.notification_id AS CHAR),
 JSON_OBJECT('type',NEW.evidence_type,'reference',NEW.evidence_reference,'sha256',NEW.evidence_sha256,'note',NEW.note),
 'delivery-evidence-v1',NEW.created_at,'t_alert_delivery_evidence',NEW.id,CONCAT('evidence:',NEW.id),NEW.created_at);
CREATE TRIGGER trg_delivery_evidence_compact_delete AFTER DELETE ON t_alert_delivery_evidence FOR EACH ROW
DELETE FROM t_system_audit_log WHERE legacy_source_table='t_alert_delivery_evidence' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_provider_receipt_compact_insert AFTER INSERT ON t_notification_provider_receipt FOR EACH ROW
INSERT INTO t_system_audit_log(operation,audit_class,outcome,target_type,target_id,operation_detail,
 detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at)
VALUES('NOTIFICATION_PROVIDER_RECEIPT','SYSTEM','SUCCESS','ALERT_NOTIFICATION',CAST(NEW.notification_id AS CHAR),
 JSON_OBJECT('callbackId',NEW.callback_id,'providerCode',NEW.provider_code,'channel',NEW.notify_channel,
  'providerMessageId',NEW.provider_message_id,'notificationId',NEW.notification_id,'eventType',NEW.event_type,
  'dtmfDigit',NEW.dtmf_digit,'payloadSha256',NEW.payload_sha256,'receivedAt',NEW.received_at),
 'provider-receipt-v1',NEW.event_time,'t_notification_provider_receipt',NEW.id,
 CONCAT(NEW.provider_code,':',NEW.callback_id),NEW.received_at);
CREATE TRIGGER trg_provider_receipt_compact_delete AFTER DELETE ON t_notification_provider_receipt FOR EACH ROW
DELETE FROM t_system_audit_log WHERE legacy_source_table='t_notification_provider_receipt' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_in_app_receipt_compact_insert AFTER INSERT ON t_in_app_notification_receipt FOR EACH ROW
INSERT INTO t_system_audit_log(user_id,operation,audit_class,outcome,target_type,target_id,operation_detail,
 detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at)
VALUES(NEW.user_id,'IN_APP_NOTIFICATION_READ','BUSINESS','SUCCESS','ALERT_NOTIFICATION',CAST(NEW.notification_id AS CHAR),
 JSON_OBJECT('readAt',NEW.read_at),'in-app-read-v1',NEW.read_at,'t_in_app_notification_receipt',NEW.id,
 CONCAT(NEW.notification_id,':',NEW.user_id),NEW.created_at);
CREATE TRIGGER trg_in_app_receipt_compact_delete AFTER DELETE ON t_in_app_notification_receipt FOR EACH ROW
DELETE FROM t_system_audit_log WHERE legacy_source_table='t_in_app_notification_receipt' AND legacy_source_id=OLD.id;
