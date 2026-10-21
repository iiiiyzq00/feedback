ALTER TABLE t_alert_notification ADD COLUMN event_type VARCHAR(50) DEFAULT NULL AFTER record_type;
ALTER TABLE t_alert_notification ADD COLUMN report_id BIGINT DEFAULT NULL AFTER alert_instance_id;
ALTER TABLE t_alert_notification ADD KEY idx_report_notification (report_id,notify_status,created_at);
ALTER TABLE t_alert_notification DROP CHECK chk_notification_required;
ALTER TABLE t_alert_notification ADD CONSTRAINT chk_notification_required CHECK (record_type <> 'NOTIFICATION' OR ((alert_instance_id IS NOT NULL OR report_id IS NOT NULL) AND notify_channel IS NOT NULL AND notify_status IS NOT NULL));
ALTER TABLE t_alert_notification ADD CONSTRAINT chk_report_notification CHECK (report_id IS NULL OR (event_type='RISK_BRIEF_PUBLISHED' AND alert_instance_id IS NULL));
CREATE INDEX idx_feedback_version_observation ON t_feedback_source(package_name,app_version,source_event_time,source_channel);
