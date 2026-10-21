-- Consolidate mutable report-AI circuit state into the shared typed configuration model.
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,config_subtype,record_status,record_version,
 effective_from,effective_to,config_desc,is_active,created_at,updated_at)
SELECT CONCAT('REPORT_AI_HEALTH:',health_key),'REPORT_AI_HEALTH','state-v1',
 JSON_OBJECT('lastSuccessAt',last_success_at),'report-ai-health-v1','ACTIVE','APPROVED',health_key,
 circuit_state,circuit_state,consecutive_failures,last_failure_at,next_probe_at,last_failure_code,1,
 updated_at,updated_at
FROM t_report_ai_health;

CREATE TRIGGER trg_report_ai_health_config_insert AFTER INSERT ON t_report_ai_health FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,scope_key,config_subtype,record_status,record_version,
 effective_from,effective_to,config_desc,is_active,created_at,updated_at)
VALUES(CONCAT('REPORT_AI_HEALTH:',NEW.health_key),'REPORT_AI_HEALTH','state-v1',
 JSON_OBJECT('lastSuccessAt',NEW.last_success_at),'report-ai-health-v1','ACTIVE','APPROVED',NEW.health_key,
 NEW.circuit_state,NEW.circuit_state,NEW.consecutive_failures,NEW.last_failure_at,NEW.next_probe_at,
 NEW.last_failure_code,1,NEW.updated_at,NEW.updated_at);

CREATE TRIGGER trg_report_ai_health_config_update AFTER UPDATE ON t_report_ai_health FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('lastSuccessAt',NEW.last_success_at),
 config_subtype=NEW.circuit_state,record_status=NEW.circuit_state,record_version=NEW.consecutive_failures,
 effective_from=NEW.last_failure_at,effective_to=NEW.next_probe_at,config_desc=NEW.last_failure_code,
 updated_at=NEW.updated_at
WHERE config_key=CONVERT(CONCAT('REPORT_AI_HEALTH:',OLD.health_key) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;

CREATE TRIGGER trg_report_ai_health_config_delete AFTER DELETE ON t_report_ai_health FOR EACH ROW
DELETE FROM t_system_config
WHERE config_key=CONVERT(CONCAT('REPORT_AI_HEALTH:',OLD.health_key) USING utf8mb4) COLLATE utf8mb4_0900_as_cs;
