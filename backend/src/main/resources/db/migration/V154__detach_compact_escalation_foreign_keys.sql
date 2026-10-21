-- Production writes now store escalation chains and targets directly in
-- t_alert_notification. These identifiers therefore reference compact rows,
-- not the legacy source tables retained temporarily for rollback.
ALTER TABLE t_alert_notification
  DROP FOREIGN KEY fk_notification_escalation_chain,
  DROP FOREIGN KEY fk_notification_escalation_target;
