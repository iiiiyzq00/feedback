-- Consolidate versioned import mapping templates into the shared configuration catalog.
INSERT INTO t_system_config(
  config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,scope_key,record_status,is_active,created_by,created_at,updated_at
)
SELECT CONCAT('IMPORT_TEMPLATE:',id),'IMPORT_TEMPLATE',version,
  JSON_OBJECT('legacyId',id,'templateName',template_name,'headers',header_columns,'mapping',field_mappings),
  schema_version,IF(is_active,'ACTIVE','RETIRED'),'APPROVED',source_channel,
  IF(is_active,'ACTIVE','INACTIVE'),is_active,created_by,created_at,updated_at
FROM t_import_template
ON DUPLICATE KEY UPDATE
  config_value=VALUES(config_value),config_schema_version=VALUES(config_schema_version),
  lifecycle_status=VALUES(lifecycle_status),scope_key=VALUES(scope_key),
  record_status=VALUES(record_status),is_active=VALUES(is_active),created_by=VALUES(created_by),
  updated_at=VALUES(updated_at);

CREATE TRIGGER trg_import_template_config_insert
AFTER INSERT ON t_import_template FOR EACH ROW
INSERT INTO t_system_config(
  config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,scope_key,record_status,is_active,created_by,created_at,updated_at
)
VALUES(
  CONCAT('IMPORT_TEMPLATE:',NEW.id),'IMPORT_TEMPLATE',NEW.version,
  JSON_OBJECT('legacyId',NEW.id,'templateName',NEW.template_name,'headers',NEW.header_columns,'mapping',NEW.field_mappings),
  NEW.schema_version,IF(NEW.is_active,'ACTIVE','RETIRED'),'APPROVED',NEW.source_channel,
  IF(NEW.is_active,'ACTIVE','INACTIVE'),NEW.is_active,NEW.created_by,NEW.created_at,NEW.updated_at
);

CREATE TRIGGER trg_import_template_config_update
AFTER UPDATE ON t_import_template FOR EACH ROW
UPDATE t_system_config SET
  config_version=NEW.version,
  config_value=JSON_OBJECT('legacyId',NEW.id,'templateName',NEW.template_name,'headers',NEW.header_columns,'mapping',NEW.field_mappings),
  config_schema_version=NEW.schema_version,
  lifecycle_status=IF(NEW.is_active,'ACTIVE','RETIRED'),
  scope_key=NEW.source_channel,
  record_status=IF(NEW.is_active,'ACTIVE','INACTIVE'),
  is_active=NEW.is_active,
  created_by=NEW.created_by,
  updated_at=NEW.updated_at
WHERE config_key=CONCAT('IMPORT_TEMPLATE:',OLD.id) AND config_version=OLD.version;

CREATE TRIGGER trg_import_template_config_delete
AFTER DELETE ON t_import_template FOR EACH ROW
DELETE FROM t_system_config
WHERE config_key=CONCAT('IMPORT_TEMPLATE:',OLD.id) AND config_version=OLD.version;
