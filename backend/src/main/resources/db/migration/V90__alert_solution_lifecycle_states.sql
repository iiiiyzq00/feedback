-- Align the persisted alert lifecycle with solution section 3.8.1 while
-- retaining RESOLVED for historical rows and rolling upgrades.
ALTER TABLE t_alert_instance DROP CHECK chk_instance_status;
ALTER TABLE t_alert_instance
  ADD CONSTRAINT chk_instance_status CHECK (instance_status IN (
    'PENDING','FIRING','ACKNOWLEDGED','SUPPRESSED','ESCALATED',
    'RECOVERED','RESOLVED','CLOSED','ARCHIVED'
  ));

ALTER TABLE t_alert_instance DROP CHECK chk_alert_terminal_time;
ALTER TABLE t_alert_instance
  ADD CONSTRAINT chk_alert_terminal_time CHECK (
    (instance_status NOT IN ('RECOVERED','RESOLVED','CLOSED','ARCHIVED') OR resolved_at IS NOT NULL)
    AND (instance_status <> 'ARCHIVED' OR archived_at IS NOT NULL)
  );
