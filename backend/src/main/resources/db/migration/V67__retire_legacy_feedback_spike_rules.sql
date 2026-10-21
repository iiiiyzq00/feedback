-- FEEDBACK_SPIKE was an early demo metric that counted all PASS feedback in a
-- rolling window.  It did not implement the baseline anomaly model defined in
-- solution 3.8.3/3.8.4 and must not remain active as a production rule.
UPDATE t_alert_rule
SET is_enabled = 0
WHERE version_status = 'ACTIVE'
  AND JSON_UNQUOTE(JSON_EXTRACT(trigger_expression, '$.metric')) = 'FEEDBACK_SPIKE';

-- Keep the immutable alert/occurrence history, but stop presenting a retired
-- demo signal as an active incident. P0 is deliberately excluded because P0
-- incidents require an operator conclusion.
UPDATE t_alert_instance
SET instance_status = 'RESOLVED',
    resolved_at = COALESCE(resolved_at, NOW(3)),
    status_reason = '旧版 FEEDBACK_SPIKE 演示规则退役：该指标仅统计窗口总量，不符合方案 3.8 的基线异常模型',
    lifecycle_version = lifecycle_version + 1
WHERE rule_code IN (
    SELECT rule_code
    FROM t_alert_rule
    WHERE JSON_UNQUOTE(JSON_EXTRACT(trigger_expression, '$.metric')) = 'FEEDBACK_SPIKE'
)
  AND instance_status IN ('FIRING', 'ACKNOWLEDGED')
  AND instance_severity <> 'P0';

UPDATE t_incident inc
JOIN t_incident_alert_link link ON link.incident_id = inc.id AND link.link_status = 'ACTIVE'
JOIN t_alert_instance ai ON ai.id = link.alert_instance_id
SET inc.incident_status = 'RESOLVED',
    inc.resolved_at = COALESCE(inc.resolved_at, NOW(3)),
    inc.resolution_summary = COALESCE(
        inc.resolution_summary,
        '关联的旧版 FEEDBACK_SPIKE 演示规则已按方案 3.8 正式治理口径退役'
    ),
    inc.lifecycle_version = inc.lifecycle_version + 1
WHERE ai.rule_code IN (
    SELECT rule_code
    FROM t_alert_rule
    WHERE JSON_UNQUOTE(JSON_EXTRACT(trigger_expression, '$.metric')) = 'FEEDBACK_SPIKE'
)
  AND inc.incident_status = 'OPEN'
  AND ai.instance_severity <> 'P0';
