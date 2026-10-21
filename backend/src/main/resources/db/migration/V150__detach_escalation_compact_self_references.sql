-- Compact rows must not reference their own legacy source through delivery-only FKs.
UPDATE t_alert_notification SET escalation_chain_id=NULL
WHERE record_type='ESCALATION_CHAIN';

UPDATE t_alert_notification SET escalation_target_id=NULL
WHERE record_type='ESCALATION_TARGET';

DROP TRIGGER trg_escalation_chain_compact_insert;
DROP TRIGGER trg_escalation_chain_compact_update;
DROP TRIGGER trg_escalation_target_compact_insert;
DROP TRIGGER trg_escalation_target_compact_update;

CREATE TRIGGER trg_escalation_chain_compact_insert AFTER INSERT ON t_alert_escalation_chain FOR EACH ROW
INSERT INTO t_alert_notification(record_type,event_type,rule_id,rule_code,alert_instance_id,incident_id,
 escalation_status,target_order,channel_sequence,owner_team,severity,legacy_source_id,target_user_id,
 ack_deadline,acknowledged_at,last_error_summary,last_provider_code,last_error_type,
 policy_version,delivery_mode,created_at,updated_at)
VALUES('ESCALATION_CHAIN','ALERT_ESCALATION_CHAIN',NEW.rule_id,NEW.rule_code,NEW.alert_instance_id,
 NEW.incident_id,NEW.chain_status,NEW.current_target_order,NEW.current_channel_order,NEW.owner_team,
 NEW.severity,NEW.id,NEW.acknowledged_by,NEW.confirmation_deadline,NEW.acknowledged_at,
 NEW.last_failure_reason,NEW.exhausted_reason_code,NEW.notification_risk_level,
 'escalation-v1','IMMEDIATE',NEW.created_at,NEW.updated_at);

CREATE TRIGGER trg_escalation_chain_compact_update AFTER UPDATE ON t_alert_escalation_chain FOR EACH ROW
UPDATE t_alert_notification SET rule_id=NEW.rule_id,rule_code=NEW.rule_code,
 alert_instance_id=NEW.alert_instance_id,incident_id=NEW.incident_id,
 escalation_status=NEW.chain_status,target_order=NEW.current_target_order,
 channel_sequence=NEW.current_channel_order,owner_team=NEW.owner_team,severity=NEW.severity,
 target_user_id=NEW.acknowledged_by,ack_deadline=NEW.confirmation_deadline,
 acknowledged_at=NEW.acknowledged_at,last_error_summary=NEW.last_failure_reason,
 last_provider_code=NEW.exhausted_reason_code,last_error_type=NEW.notification_risk_level,
 updated_at=NEW.updated_at
WHERE escalation_compact_key=CONVERT(CONCAT('CHAIN:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_escalation_target_compact_insert AFTER INSERT ON t_alert_escalation_target FOR EACH ROW
INSERT INTO t_alert_notification(record_type,event_type,escalation_chain_id,escalation_status,
 target_order,target_type,legacy_source_id,target_user_id,notify_recipients,notify_content,
 policy_version,delivery_mode,created_at,updated_at)
VALUES('ESCALATION_TARGET','ALERT_ESCALATION_TARGET',NEW.chain_id,NEW.target_status,
 NEW.target_order,NEW.target_type,NEW.id,NEW.user_id,NEW.channels,NEW.display_name_snapshot,
 'escalation-v1','IMMEDIATE',NEW.created_at,NEW.updated_at);

CREATE TRIGGER trg_escalation_target_compact_update AFTER UPDATE ON t_alert_escalation_target FOR EACH ROW
UPDATE t_alert_notification SET escalation_chain_id=NEW.chain_id,
 escalation_status=NEW.target_status,target_order=NEW.target_order,target_type=NEW.target_type,
 target_user_id=NEW.user_id,notify_recipients=NEW.channels,notify_content=NEW.display_name_snapshot,
 updated_at=NEW.updated_at
WHERE escalation_compact_key=CONVERT(CONCAT('TARGET:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;
