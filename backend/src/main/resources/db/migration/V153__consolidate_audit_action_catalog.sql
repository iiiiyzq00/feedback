-- Consolidate the formal audit-action catalog into the shared typed configuration aggregate.
INSERT INTO t_system_config(
  config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,owner_team,config_subtype,record_status,
  config_desc,is_active,created_at,updated_at)
SELECT CONCAT('AUDIT_ACTION:',operation),'AUDIT_ACTION_CATALOG','v1',
       JSON_OBJECT('sensitiveAction',sensitive_action),'audit-action-v1',
       'ACTIVE','APPROVED','SECURITY_GOVERNANCE',audit_class,'ACTIVE',
       description,1,NOW(3),NOW(3)
FROM t_system_audit_action_catalog
ON DUPLICATE KEY UPDATE
  config_type=VALUES(config_type),config_value=VALUES(config_value),
  config_schema_version=VALUES(config_schema_version),
  lifecycle_status=VALUES(lifecycle_status),approval_status=VALUES(approval_status),
  owner_team=VALUES(owner_team),config_subtype=VALUES(config_subtype),
  record_status=VALUES(record_status),config_desc=VALUES(config_desc),is_active=1;

DELIMITER $$
CREATE TRIGGER trg_audit_action_catalog_compact_insert
AFTER INSERT ON t_system_audit_action_catalog FOR EACH ROW
BEGIN
  INSERT INTO t_system_config(
    config_key,config_type,config_version,config_value,config_schema_version,
    lifecycle_status,approval_status,owner_team,config_subtype,record_status,
    config_desc,is_active,created_at,updated_at)
  VALUES(CONCAT('AUDIT_ACTION:',NEW.operation),'AUDIT_ACTION_CATALOG','v1',
    JSON_OBJECT('sensitiveAction',NEW.sensitive_action),'audit-action-v1',
    'ACTIVE','APPROVED','SECURITY_GOVERNANCE',NEW.audit_class,'ACTIVE',
    NEW.description,1,NOW(3),NOW(3));
END$$

CREATE TRIGGER trg_audit_action_catalog_compact_update
AFTER UPDATE ON t_system_audit_action_catalog FOR EACH ROW
BEGIN
  UPDATE t_system_config
  SET config_key=CONCAT('AUDIT_ACTION:',NEW.operation),
      config_value=JSON_OBJECT('sensitiveAction',NEW.sensitive_action),
      config_subtype=NEW.audit_class,config_desc=NEW.description,
      updated_at=NOW(3)
  WHERE config_type='AUDIT_ACTION_CATALOG'
    AND config_key=CONCAT('AUDIT_ACTION:',OLD.operation)
    AND config_version='v1';
END$$

CREATE TRIGGER trg_audit_action_catalog_compact_delete
AFTER DELETE ON t_system_audit_action_catalog FOR EACH ROW
BEGIN
  DELETE FROM t_system_config
  WHERE config_type='AUDIT_ACTION_CATALOG'
    AND config_key=CONCAT('AUDIT_ACTION:',OLD.operation)
    AND config_version='v1';
END$$
DELIMITER ;
