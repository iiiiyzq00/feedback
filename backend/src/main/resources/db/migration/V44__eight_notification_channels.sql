-- MySQL DDL is not transactional. Drop the legacy constraint before translating
-- stored values so a failed migration can be repaired and safely re-run.
ALTER TABLE t_alert_notification DROP CHECK chk_notify_channel;

UPDATE t_alert_notification SET notify_channel='FEISHU_CARD' WHERE notify_channel='FEISHU';
UPDATE t_alert_delivery_attempt SET notify_channel='FEISHU_CARD' WHERE notify_channel='FEISHU';

-- Keep this update JSON-native. JSON_REPLACE on the serialized document also
-- covers FEISHU at any array position without depending on CAST(... AS JSON),
-- which is not accepted consistently across supported MySQL 8 patch releases.
UPDATE t_alert_rule
SET routing_config = REPLACE(routing_config, '"FEISHU"', '"FEISHU_CARD"')
WHERE JSON_CONTAINS(JSON_EXTRACT(routing_config,'$.channels'),'"FEISHU"');

ALTER TABLE t_alert_notification ADD CONSTRAINT chk_notify_channel CHECK (
  notify_channel IS NULL OR notify_channel IN
  ('FEISHU_CARD','FEISHU_ALL','FEISHU_DM','FEISHU_TASK','SMS','VOICE_CALL','EMAIL','IN_APP'));
