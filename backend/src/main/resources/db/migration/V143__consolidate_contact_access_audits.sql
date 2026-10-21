-- Consolidate immutable authorized-contact access evidence into the append-only audit stream.
INSERT INTO t_system_audit_log(
  user_id,operation,audit_class,outcome,target_type,target_id,correlation_id,operation_detail,
  detail_schema_version,ip_address,event_time,legacy_source_table,legacy_source_id,created_at
)
SELECT user_id,'CONTACT_DECRYPT','SECURITY','SUCCESS','FEEDBACK_CONTACT',CAST(source_id AS CHAR),work_order_ref,
  JSON_OBJECT('sourceId',source_id,'workOrderRef',work_order_ref,'purpose',access_purpose,'contactTypes',contact_types),
  'contact-access-v1',ip_address,accessed_at,'t_contact_access_audit',id,accessed_at
FROM t_contact_access_audit
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),operation_detail=VALUES(operation_detail),
  ip_address=VALUES(ip_address),event_time=VALUES(event_time),created_at=VALUES(created_at);

CREATE TRIGGER trg_contact_access_security_audit_insert
AFTER INSERT ON t_contact_access_audit FOR EACH ROW
INSERT INTO t_system_audit_log(
  user_id,operation,audit_class,outcome,target_type,target_id,correlation_id,operation_detail,
  detail_schema_version,ip_address,event_time,legacy_source_table,legacy_source_id,created_at
)
VALUES(NEW.user_id,'CONTACT_DECRYPT','SECURITY','SUCCESS','FEEDBACK_CONTACT',CAST(NEW.source_id AS CHAR),NEW.work_order_ref,
  JSON_OBJECT('sourceId',NEW.source_id,'workOrderRef',NEW.work_order_ref,'purpose',NEW.access_purpose,
    'contactTypes',NEW.contact_types),
  'contact-access-v1',NEW.ip_address,NEW.accessed_at,'t_contact_access_audit',NEW.id,NEW.accessed_at);
