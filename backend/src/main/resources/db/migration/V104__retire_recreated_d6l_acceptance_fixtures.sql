-- V92 retired fixtures that existed at that migration. A later acceptance
-- cleanup attempted to delete immutable audit rows before deleting fixtures,
-- so MySQL stopped the remaining cleanup statements. Retire those later leaks.
UPDATE t_alert_rule SET rule_name='D6L业务告警（历史验收）',version_status='RETIRED',is_enabled=0
WHERE rule_code LIKE 'D6L_RULE_%';

UPDATE t_alert_instance SET instance_status='ARCHIVED',resolved_at=COALESCE(resolved_at,NOW(3)),archived_at=COALESCE(archived_at,NOW(3))
WHERE rule_code LIKE 'D6L_RULE_%' AND instance_status<>'ARCHIVED';

UPDATE t_feedback_content c JOIN t_feedback_source s ON s.id=c.source_id
SET c.is_deleted=1 WHERE s.source_record_id LIKE 'D6L-%';

UPDATE t_cluster SET cluster_status='ARCHIVED' WHERE cluster_name LIKE 'D6L-%';
