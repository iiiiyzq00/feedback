-- Earlier local acceptance scripts could be interrupted before their cleanup
-- trap ran.  Keep their audit rows, but remove them from production-facing
-- rule, alert and dashboard queries.
UPDATE t_alert_rule
SET rule_name = 'D6L业务告警（历史验收）', version_status = 'RETIRED', is_enabled = 0
WHERE rule_code LIKE 'D6L_RULE_%';

UPDATE t_alert_instance i
JOIN t_alert_rule r ON r.id = i.rule_id
SET i.instance_status = 'ARCHIVED',
    i.resolved_at = COALESCE(i.resolved_at, NOW(3)),
    i.archived_at = COALESCE(i.archived_at, NOW(3))
WHERE r.rule_code LIKE 'D6L_RULE_%'
  AND i.instance_status <> 'ARCHIVED';

UPDATE t_feedback_content c
JOIN t_feedback_source s ON s.id = c.source_id
SET c.is_deleted = 1
WHERE s.source_record_id LIKE 'D6L-%';

UPDATE t_cluster
SET cluster_status = 'ARCHIVED'
WHERE cluster_name LIKE 'D6L-%';
