-- Complete the governed snapshot lifecycle for index outbox rows created by V32.
UPDATE t_ai_task
SET input_snapshot=JSON_OBJECT('entityType','FEEDBACK','entityId',source_id,'operation','UPSERT'),
    snapshot_retention_until=DATE_ADD(COALESCE(created_at,NOW(3)),INTERVAL 30 DAY)
WHERE record_type='INDEX_OUTBOX' AND input_snapshot IS NOT NULL AND snapshot_retention_until IS NULL;
