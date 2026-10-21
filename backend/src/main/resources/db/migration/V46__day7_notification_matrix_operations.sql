-- D7-N01..N12: governed severity/channel matrix and delivery operations indexes.
CREATE TABLE t_alert_notification_policy (
  severity VARCHAR(5) PRIMARY KEY,
  required_channels JSON NOT NULL,
  allowed_channels JSON NOT NULL,
  delivery_mode VARCHAR(20) NOT NULL,
  schedule_time TIME DEFAULT NULL,
  timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Shanghai',
  policy_version VARCHAR(20) NOT NULL DEFAULT 'v1',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_notification_policy_severity CHECK (severity IN ('P0','P1','P2','P3')),
  CONSTRAINT chk_notification_delivery_mode CHECK (delivery_mode IN ('IMMEDIATE','DAILY_DIGEST')),
  CONSTRAINT chk_notification_digest_schedule CHECK ((delivery_mode='DAILY_DIGEST' AND schedule_time IS NOT NULL) OR delivery_mode='IMMEDIATE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='告警级别通知矩阵治理策略';

INSERT INTO t_alert_notification_policy(severity,required_channels,allowed_channels,delivery_mode,schedule_time) VALUES
('P0',JSON_ARRAY('FEISHU_CARD','FEISHU_ALL','FEISHU_DM','FEISHU_TASK','SMS','VOICE_CALL','EMAIL','IN_APP'),JSON_ARRAY('FEISHU_CARD','FEISHU_ALL','FEISHU_DM','FEISHU_TASK','SMS','VOICE_CALL','EMAIL','IN_APP'),'IMMEDIATE',NULL),
('P1',JSON_ARRAY('FEISHU_CARD','FEISHU_DM','FEISHU_TASK','SMS','EMAIL','IN_APP'),JSON_ARRAY('FEISHU_CARD','FEISHU_DM','FEISHU_TASK','SMS','EMAIL','IN_APP'),'IMMEDIATE',NULL),
('P2',JSON_ARRAY('FEISHU_CARD','IN_APP'),JSON_ARRAY('FEISHU_CARD','EMAIL','IN_APP'),'IMMEDIATE',NULL),
('P3',JSON_ARRAY('IN_APP'),JSON_ARRAY('IN_APP'),'DAILY_DIGEST','09:00:00');

ALTER TABLE t_alert_notification
  ADD COLUMN policy_version VARCHAR(20) NOT NULL DEFAULT 'v1' AFTER notify_channel,
  ADD COLUMN delivery_mode VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE' AFTER policy_version,
  ADD CONSTRAINT chk_notification_delivery_mode_row CHECK (delivery_mode IN ('IMMEDIATE','DAILY_DIGEST'));

CREATE INDEX idx_notification_channel_status_time ON t_alert_notification(record_type,notify_channel,notify_status,created_at);
CREATE INDEX idx_delivery_attempt_channel_time ON t_alert_delivery_attempt(notify_channel,created_at,attempt_status);
