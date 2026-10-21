UPDATE t_alert_notification
SET notify_content=JSON_QUOTE(notify_content)
WHERE record_type='ESCALATION_TARGET' AND notify_content IS NOT NULL AND JSON_VALID(notify_content)=0;

DROP TRIGGER trg_escalation_target_compact_insert;
DROP TRIGGER trg_escalation_target_compact_update;

CREATE TRIGGER trg_escalation_target_compact_insert AFTER INSERT ON t_alert_escalation_target FOR EACH ROW
INSERT INTO t_alert_notification(record_type,event_type,escalation_chain_id,escalation_status,
 target_order,target_type,legacy_source_id,target_user_id,notify_recipients,notify_content,
 policy_version,delivery_mode,created_at,updated_at)
VALUES('ESCALATION_TARGET','ALERT_ESCALATION_TARGET',NEW.chain_id,NEW.target_status,
 NEW.target_order,NEW.target_type,NEW.id,NEW.user_id,NEW.channels,JSON_QUOTE(NEW.display_name_snapshot),
 'escalation-v1','IMMEDIATE',NEW.created_at,NEW.updated_at);

CREATE TRIGGER trg_escalation_target_compact_update AFTER UPDATE ON t_alert_escalation_target FOR EACH ROW
UPDATE t_alert_notification SET escalation_chain_id=NEW.chain_id,
 escalation_status=NEW.target_status,target_order=NEW.target_order,target_type=NEW.target_type,
 target_user_id=NEW.user_id,notify_recipients=NEW.channels,
 notify_content=JSON_QUOTE(NEW.display_name_snapshot),updated_at=NEW.updated_at
WHERE escalation_compact_key=CONVERT(CONCAT('TARGET:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;
