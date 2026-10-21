-- Consolidate transport-only SDK telemetry and mutable application release facts.
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,http_status,duration_ms,error_type,fact_status,evaluation_snapshot,evaluation_time,created_at)
SELECT NULL,'SDK_REQUEST','SDK_REQUEST',app_id,'t_sdk_request_telemetry',id,http_status,duration_ms,
 error_code,result_type,JSON_OBJECT('appId',app_id,'httpStatus',http_status,'resultType',result_type,
 'errorCode',error_code,'durationMs',duration_ms),occurred_at,occurred_at
FROM t_sdk_request_telemetry
ON DUPLICATE KEY UPDATE http_status=VALUES(http_status),duration_ms=VALUES(duration_ms),
 error_type=VALUES(error_type),fact_status=VALUES(fact_status),evaluation_snapshot=VALUES(evaluation_snapshot),
 evaluation_time=VALUES(evaluation_time);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,config_subtype,subject_user_id,record_status,effective_from,
 is_active,created_by,created_at,updated_at)
SELECT CONCAT('APP_VERSION_RELEASE:',id),'APP_VERSION_RELEASE',app_version,JSON_OBJECT('legacyId',id,
 'packageName',package_name,'appVersion',app_version,'controlVersion',control_version),
 'app-release-v1',release_status,IF(release_status='ACTIVE','APPROVED','PENDING'),package_name,
 app_version,created_by,release_status,released_at,release_status='ACTIVE',created_by,created_at,updated_at
FROM t_app_version_release_fact
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),lifecycle_status=VALUES(lifecycle_status),
 approval_status=VALUES(approval_status),scope_key=VALUES(scope_key),config_subtype=VALUES(config_subtype),
 record_status=VALUES(record_status),effective_from=VALUES(effective_from),is_active=VALUES(is_active),
 updated_at=VALUES(updated_at);

CREATE TRIGGER trg_sdk_telemetry_compact_insert AFTER INSERT ON t_sdk_request_telemetry FOR EACH ROW
INSERT INTO t_alert_occurrence(instance_id,occurrence_type,metric_code,dimension_key,legacy_source_table,
 legacy_source_id,http_status,duration_ms,error_type,fact_status,evaluation_snapshot,evaluation_time,created_at)
VALUES(NULL,'SDK_REQUEST','SDK_REQUEST',NEW.app_id,'t_sdk_request_telemetry',NEW.id,NEW.http_status,
 NEW.duration_ms,NEW.error_code,NEW.result_type,JSON_OBJECT('appId',NEW.app_id,'httpStatus',NEW.http_status,
 'resultType',NEW.result_type,'errorCode',NEW.error_code,'durationMs',NEW.duration_ms),NEW.occurred_at,NEW.occurred_at);
CREATE TRIGGER trg_sdk_telemetry_compact_delete AFTER DELETE ON t_sdk_request_telemetry FOR EACH ROW
DELETE FROM t_alert_occurrence WHERE legacy_source_table='t_sdk_request_telemetry' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_app_release_config_insert AFTER INSERT ON t_app_version_release_fact FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,config_subtype,subject_user_id,record_status,effective_from,
 is_active,created_by,created_at,updated_at)
VALUES(CONCAT('APP_VERSION_RELEASE:',NEW.id),'APP_VERSION_RELEASE',NEW.app_version,JSON_OBJECT('legacyId',NEW.id,
 'packageName',NEW.package_name,'appVersion',NEW.app_version,'controlVersion',NEW.control_version),
 'app-release-v1',NEW.release_status,IF(NEW.release_status='ACTIVE','APPROVED','PENDING'),NEW.package_name,
 NEW.app_version,NEW.created_by,NEW.release_status,NEW.released_at,NEW.release_status='ACTIVE',NEW.created_by,
 NEW.created_at,NEW.updated_at);
CREATE TRIGGER trg_app_release_config_update AFTER UPDATE ON t_app_version_release_fact FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('legacyId',NEW.id,'packageName',NEW.package_name,
 'appVersion',NEW.app_version,'controlVersion',NEW.control_version),lifecycle_status=NEW.release_status,
 approval_status=IF(NEW.release_status='ACTIVE','APPROVED','PENDING'),scope_key=NEW.package_name,
 config_subtype=NEW.app_version,record_status=NEW.release_status,effective_from=NEW.released_at,
 is_active=NEW.release_status='ACTIVE',updated_at=NEW.updated_at
WHERE config_key=CONCAT('APP_VERSION_RELEASE:',OLD.id) AND config_version=OLD.app_version;
CREATE TRIGGER trg_app_release_config_delete AFTER DELETE ON t_app_version_release_fact FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('APP_VERSION_RELEASE:',OLD.id) AND config_version=OLD.app_version;
