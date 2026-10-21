-- Consolidate mutable escalation chains and targets into the alert notification aggregate.
ALTER TABLE t_alert_notification
  ADD COLUMN incident_id BIGINT NULL AFTER alert_instance_id,
  ADD COLUMN escalation_status VARCHAR(20) COLLATE utf8mb4_0900_as_cs NULL AFTER escalation_target_id,
  ADD COLUMN target_order INT NULL AFTER escalation_status,
  ADD COLUMN target_type VARCHAR(20) COLLATE utf8mb4_0900_as_cs NULL AFTER target_order,
  ADD COLUMN owner_team VARCHAR(50) COLLATE utf8mb4_0900_as_cs NULL AFTER target_type,
  ADD COLUMN severity VARCHAR(5) COLLATE utf8mb4_0900_as_cs NULL AFTER owner_team,
  ADD COLUMN legacy_source_id BIGINT NULL AFTER severity,
  ADD COLUMN escalation_compact_key VARCHAR(80) COLLATE utf8mb4_0900_as_cs
    GENERATED ALWAYS AS (CASE
      WHEN record_type='ESCALATION_CHAIN' THEN CONCAT('CHAIN:',legacy_source_id)
      WHEN record_type='ESCALATION_TARGET' THEN CONCAT('TARGET:',legacy_source_id)
      ELSE NULL END) STORED,
  DROP CONSTRAINT chk_alert_notification_record_type,
  ADD CONSTRAINT chk_alert_notification_record_type CHECK(record_type IN
    ('NOTIFICATION','EVALUATION_TASK','SUPPRESSION','ESCALATION_CHAIN','ESCALATION_TARGET')),
  ADD UNIQUE KEY uk_notification_escalation_compact(escalation_compact_key),
  ADD KEY idx_escalation_compact_due(record_type,escalation_status,ack_deadline,id),
  ADD KEY idx_escalation_compact_alert(record_type,alert_instance_id),
  ADD KEY idx_escalation_compact_target(record_type,escalation_chain_id,target_order,id);

INSERT INTO t_alert_notification(record_type,event_type,rule_id,rule_code,alert_instance_id,incident_id,
 escalation_chain_id,escalation_status,target_order,channel_sequence,owner_team,severity,legacy_source_id,
 target_user_id,ack_deadline,acknowledged_at,last_error_summary,last_provider_code,last_error_type,
 policy_version,delivery_mode,created_at,updated_at)
SELECT 'ESCALATION_CHAIN','ALERT_ESCALATION_CHAIN',rule_id,rule_code,alert_instance_id,incident_id,id,
 chain_status,current_target_order,current_channel_order,owner_team,severity,id,acknowledged_by,
 confirmation_deadline,acknowledged_at,last_failure_reason,exhausted_reason_code,
 notification_risk_level,'escalation-v1','IMMEDIATE',created_at,updated_at
FROM t_alert_escalation_chain;

INSERT INTO t_alert_notification(record_type,event_type,escalation_chain_id,escalation_target_id,
 escalation_status,target_order,target_type,legacy_source_id,target_user_id,notify_recipients,
 notify_content,policy_version,delivery_mode,created_at,updated_at)
SELECT 'ESCALATION_TARGET','ALERT_ESCALATION_TARGET',chain_id,id,target_status,target_order,target_type,
 id,user_id,channels,display_name_snapshot,'escalation-v1','IMMEDIATE',created_at,updated_at
FROM t_alert_escalation_target;

CREATE TRIGGER trg_escalation_chain_compact_insert AFTER INSERT ON t_alert_escalation_chain FOR EACH ROW
INSERT INTO t_alert_notification(record_type,event_type,rule_id,rule_code,alert_instance_id,incident_id,
 escalation_chain_id,escalation_status,target_order,channel_sequence,owner_team,severity,legacy_source_id,
 target_user_id,ack_deadline,acknowledged_at,last_error_summary,last_provider_code,last_error_type,
 policy_version,delivery_mode,created_at,updated_at)
VALUES('ESCALATION_CHAIN','ALERT_ESCALATION_CHAIN',NEW.rule_id,NEW.rule_code,NEW.alert_instance_id,
 NEW.incident_id,NEW.id,NEW.chain_status,NEW.current_target_order,NEW.current_channel_order,NEW.owner_team,
 NEW.severity,NEW.id,NEW.acknowledged_by,NEW.confirmation_deadline,NEW.acknowledged_at,
 NEW.last_failure_reason,NEW.exhausted_reason_code,NEW.notification_risk_level,
 'escalation-v1','IMMEDIATE',NEW.created_at,NEW.updated_at);

CREATE TRIGGER trg_escalation_chain_compact_update AFTER UPDATE ON t_alert_escalation_chain FOR EACH ROW
UPDATE t_alert_notification SET rule_id=NEW.rule_id,rule_code=NEW.rule_code,
 alert_instance_id=NEW.alert_instance_id,incident_id=NEW.incident_id,escalation_chain_id=NEW.id,
 escalation_status=NEW.chain_status,target_order=NEW.current_target_order,
 channel_sequence=NEW.current_channel_order,owner_team=NEW.owner_team,severity=NEW.severity,
 target_user_id=NEW.acknowledged_by,ack_deadline=NEW.confirmation_deadline,
 acknowledged_at=NEW.acknowledged_at,last_error_summary=NEW.last_failure_reason,
 last_provider_code=NEW.exhausted_reason_code,last_error_type=NEW.notification_risk_level,
 updated_at=NEW.updated_at
WHERE escalation_compact_key=CONVERT(CONCAT('CHAIN:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_escalation_chain_compact_delete AFTER DELETE ON t_alert_escalation_chain FOR EACH ROW
DELETE FROM t_alert_notification
WHERE escalation_compact_key=CONVERT(CONCAT('CHAIN:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_escalation_target_compact_insert AFTER INSERT ON t_alert_escalation_target FOR EACH ROW
INSERT INTO t_alert_notification(record_type,event_type,escalation_chain_id,escalation_target_id,
 escalation_status,target_order,target_type,legacy_source_id,target_user_id,notify_recipients,
 notify_content,policy_version,delivery_mode,created_at,updated_at)
VALUES('ESCALATION_TARGET','ALERT_ESCALATION_TARGET',NEW.chain_id,NEW.id,NEW.target_status,
 NEW.target_order,NEW.target_type,NEW.id,NEW.user_id,NEW.channels,NEW.display_name_snapshot,
 'escalation-v1','IMMEDIATE',NEW.created_at,NEW.updated_at);

CREATE TRIGGER trg_escalation_target_compact_update AFTER UPDATE ON t_alert_escalation_target FOR EACH ROW
UPDATE t_alert_notification SET escalation_chain_id=NEW.chain_id,escalation_target_id=NEW.id,
 escalation_status=NEW.target_status,target_order=NEW.target_order,target_type=NEW.target_type,
 target_user_id=NEW.user_id,notify_recipients=NEW.channels,notify_content=NEW.display_name_snapshot,
 updated_at=NEW.updated_at
WHERE escalation_compact_key=CONVERT(CONCAT('TARGET:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_escalation_target_compact_delete AFTER DELETE ON t_alert_escalation_target FOR EACH ROW
DELETE FROM t_alert_notification
WHERE escalation_compact_key=CONVERT(CONCAT('TARGET:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;
