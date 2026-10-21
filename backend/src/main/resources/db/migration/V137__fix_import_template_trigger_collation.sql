-- Keep trigger key comparisons deterministic across connection collations.
DROP TRIGGER trg_import_template_config_update;
DROP TRIGGER trg_import_template_config_delete;

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
WHERE config_key=CONVERT(CONCAT('IMPORT_TEMPLATE:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs
  AND config_version=OLD.version;

CREATE TRIGGER trg_import_template_config_delete
AFTER DELETE ON t_import_template FOR EACH ROW
DELETE FROM t_system_config
WHERE config_key=CONVERT(CONCAT('IMPORT_TEMPLATE:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs
  AND config_version=OLD.version;
