-- Consolidate immutable knowledge recommendation/adoption/effectiveness events.
INSERT INTO t_system_audit_log(
  user_id,operation,audit_class,outcome,target_type,target_id,correlation_id,operation_detail,
  detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at
)
SELECT actor_id,CONCAT('KNOWLEDGE_USAGE_',event_type),'BUSINESS','SUCCESS','KNOWLEDGE',CAST(knowledge_id AS CHAR),
  CONCAT('INCIDENT:',incident_id),JSON_OBJECT('knowledgeId',knowledge_id,'incidentId',incident_id,
    'alertInstanceId',alert_instance_id,'eventType',event_type,'reason',reason),
  'knowledge-usage-v1',created_at,'t_knowledge_usage_event',id,
  CONCAT(knowledge_id,':',incident_id,':',event_type),created_at
FROM t_knowledge_usage_event
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),operation_detail=VALUES(operation_detail),
  event_time=VALUES(event_time),created_at=VALUES(created_at);

CREATE TRIGGER trg_knowledge_usage_audit_insert
AFTER INSERT ON t_knowledge_usage_event FOR EACH ROW
INSERT INTO t_system_audit_log(
  user_id,operation,audit_class,outcome,target_type,target_id,correlation_id,operation_detail,
  detail_schema_version,event_time,legacy_source_table,legacy_source_id,idempotency_key,created_at
)
VALUES(NEW.actor_id,CONCAT('KNOWLEDGE_USAGE_',NEW.event_type),'BUSINESS','SUCCESS','KNOWLEDGE',CAST(NEW.knowledge_id AS CHAR),
  CONCAT('INCIDENT:',NEW.incident_id),JSON_OBJECT('knowledgeId',NEW.knowledge_id,'incidentId',NEW.incident_id,
    'alertInstanceId',NEW.alert_instance_id,'eventType',NEW.event_type,'reason',NEW.reason),
  'knowledge-usage-v1',NEW.created_at,'t_knowledge_usage_event',NEW.id,
  CONCAT(NEW.knowledge_id,':',NEW.incident_id,':',NEW.event_type),NEW.created_at);

CREATE TRIGGER trg_knowledge_usage_audit_delete
AFTER DELETE ON t_knowledge_usage_event FOR EACH ROW
DELETE FROM t_system_audit_log
WHERE legacy_source_table='t_knowledge_usage_event' AND legacy_source_id=OLD.id;
