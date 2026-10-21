ALTER TABLE t_alert_escalation_chain
  ADD COLUMN exhausted_reason_code VARCHAR(40) DEFAULT NULL AFTER last_failure_reason,
  ADD COLUMN notification_risk_level VARCHAR(5) DEFAULT NULL AFTER exhausted_reason_code,
  ADD CONSTRAINT chk_escalation_exhausted_reason CHECK (
    exhausted_reason_code IS NULL OR exhausted_reason_code IN ('NO_ELIGIBLE_ONCALL','DELIVERY_FAILED','ACK_TIMEOUT','CHAIN_EXHAUSTED')
  ),
  ADD CONSTRAINT chk_escalation_notification_risk CHECK (
    notification_risk_level IS NULL OR notification_risk_level IN ('P0','P1','P2','P3')
  );

-- Existing empty chains can be identified without guessing whether a configured target
-- failed delivery or acknowledgement. Incident severities are intentionally not rewritten.
UPDATE t_alert_escalation_chain c
SET c.exhausted_reason_code='NO_ELIGIBLE_ONCALL',
    c.notification_risk_level='P0',
    c.last_failure_reason='主责团队未配置当前有效值班人或团队负责人，告警尚未通知到具体责任人'
WHERE c.chain_status='EXHAUSTED'
  AND NOT EXISTS (SELECT 1 FROM t_alert_escalation_target t WHERE t.chain_id=c.id);
