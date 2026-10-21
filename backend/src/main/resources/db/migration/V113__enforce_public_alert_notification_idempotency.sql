ALTER TABLE t_alert_notification
  ADD COLUMN alert_fired_dedupe_key VARCHAR(100) GENERATED ALWAYS AS (
    CASE
      WHEN alert_instance_id IS NOT NULL AND event_type='ALERT_FIRED'
      THEN CONCAT(alert_instance_id,':',notify_channel)
      ELSE NULL
    END
  ) STORED,
  ADD UNIQUE KEY uk_alert_fired_channel_once(alert_fired_dedupe_key);
