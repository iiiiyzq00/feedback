-- Consolidate masking SLA policies and exact-term NER allowlist configuration.
ALTER TABLE t_system_config MODIFY COLUMN config_version VARCHAR(30) COLLATE utf8mb4_0900_as_cs NOT NULL;
ALTER TABLE t_system_config MODIFY COLUMN config_schema_version VARCHAR(30) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'v1';

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,scope_key,config_subtype,record_status,record_version,
  is_active,created_at,updated_at)
SELECT CONCAT('MASKING_REVIEW_SLA:',id),'MASKING_REVIEW_SLA',policy_version,
  JSON_OBJECT('legacyId',id,'maxMinutes',max_minutes,'effectiveCalendar',effective_calendar),
  'masking-review-sla-v1',IF(is_active,'ACTIVE','RETIRED'),'APPROVED',queue_type,urgency_class,
  IF(is_active,'ACTIVE','INACTIVE'),max_minutes,is_active,created_at,created_at
FROM t_masking_review_sla_policy
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),lifecycle_status=VALUES(lifecycle_status),
  scope_key=VALUES(scope_key),config_subtype=VALUES(config_subtype),record_status=VALUES(record_status),
  record_version=VALUES(record_version),is_active=VALUES(is_active),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,scope_key,record_status,record_version,effective_to,config_desc,
  change_reason,is_active,created_by,created_at,updated_at)
SELECT CONCAT('NER_ALLOWLIST:',id),'NER_ALLOWLIST',list_version,JSON_OBJECT('legacyId',id),
  'ner-allowlist-v1',term_status,'APPROVED',term_type,term_status,lifecycle_version,retired_at,
  term_value,reason,term_status='ACTIVE',created_by,created_at,COALESCE(retired_at,created_at)
FROM t_ner_allowlist
ON DUPLICATE KEY UPDATE lifecycle_status=VALUES(lifecycle_status),scope_key=VALUES(scope_key),
  record_status=VALUES(record_status),record_version=VALUES(record_version),effective_to=VALUES(effective_to),
  config_desc=VALUES(config_desc),change_reason=VALUES(change_reason),is_active=VALUES(is_active),
  updated_at=VALUES(updated_at);

CREATE TRIGGER trg_masking_sla_config_insert AFTER INSERT ON t_masking_review_sla_policy FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,scope_key,config_subtype,record_status,record_version,is_active,created_at,updated_at)
VALUES(CONCAT('MASKING_REVIEW_SLA:',NEW.id),'MASKING_REVIEW_SLA',NEW.policy_version,
  JSON_OBJECT('legacyId',NEW.id,'maxMinutes',NEW.max_minutes,'effectiveCalendar',NEW.effective_calendar),
  'masking-review-sla-v1',IF(NEW.is_active,'ACTIVE','RETIRED'),'APPROVED',NEW.queue_type,NEW.urgency_class,
  IF(NEW.is_active,'ACTIVE','INACTIVE'),NEW.max_minutes,NEW.is_active,NEW.created_at,NEW.created_at);

CREATE TRIGGER trg_masking_sla_config_update AFTER UPDATE ON t_masking_review_sla_policy FOR EACH ROW
UPDATE t_system_config SET config_version=NEW.policy_version,
  config_value=JSON_OBJECT('legacyId',NEW.id,'maxMinutes',NEW.max_minutes,'effectiveCalendar',NEW.effective_calendar),
  lifecycle_status=IF(NEW.is_active,'ACTIVE','RETIRED'),scope_key=NEW.queue_type,
  config_subtype=NEW.urgency_class,record_status=IF(NEW.is_active,'ACTIVE','INACTIVE'),
  record_version=NEW.max_minutes,is_active=NEW.is_active,updated_at=NOW(3)
WHERE config_key=CONVERT(CONCAT('MASKING_REVIEW_SLA:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs
  AND config_version=OLD.policy_version;

CREATE TRIGGER trg_masking_sla_config_delete AFTER DELETE ON t_masking_review_sla_policy FOR EACH ROW
DELETE FROM t_system_config
WHERE config_key=CONVERT(CONCAT('MASKING_REVIEW_SLA:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs
  AND config_version=OLD.policy_version;

CREATE TRIGGER trg_ner_allowlist_config_insert AFTER INSERT ON t_ner_allowlist FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
  lifecycle_status,approval_status,scope_key,record_status,record_version,effective_to,config_desc,
  change_reason,is_active,created_by,created_at,updated_at)
VALUES(CONCAT('NER_ALLOWLIST:',NEW.id),'NER_ALLOWLIST',NEW.list_version,JSON_OBJECT('legacyId',NEW.id),
  'ner-allowlist-v1',NEW.term_status,'APPROVED',NEW.term_type,NEW.term_status,NEW.lifecycle_version,
  NEW.retired_at,NEW.term_value,NEW.reason,NEW.term_status='ACTIVE',NEW.created_by,NEW.created_at,
  COALESCE(NEW.retired_at,NEW.created_at));

CREATE TRIGGER trg_ner_allowlist_config_update AFTER UPDATE ON t_ner_allowlist FOR EACH ROW
UPDATE t_system_config SET config_version=NEW.list_version,lifecycle_status=NEW.term_status,
  scope_key=NEW.term_type,record_status=NEW.term_status,record_version=NEW.lifecycle_version,
  effective_to=NEW.retired_at,config_desc=NEW.term_value,change_reason=NEW.reason,
  is_active=NEW.term_status='ACTIVE',updated_at=NOW(3)
WHERE config_key=CONVERT(CONCAT('NER_ALLOWLIST:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs
  AND config_version=OLD.list_version;

CREATE TRIGGER trg_ner_allowlist_config_delete AFTER DELETE ON t_ner_allowlist FOR EACH ROW
DELETE FROM t_system_config
WHERE config_key=CONVERT(CONCAT('NER_ALLOWLIST:',OLD.id) USING utf8mb4) COLLATE utf8mb4_0900_as_cs
  AND config_version=OLD.list_version;
