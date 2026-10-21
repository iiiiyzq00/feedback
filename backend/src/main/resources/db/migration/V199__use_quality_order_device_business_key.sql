-- Quality return orders may contain multiple devices. Re-key existing imported rows with the
-- same deterministic contract used by ImportBatchService: normalized return order + DEVICE_SN
-- identity fingerprint. No plaintext device identifier is exposed in the source record id.
UPDATE t_feedback_source s
JOIN t_channel_quality q ON q.source_id = s.id
JOIN t_feedback_identity i
  ON i.source_id = s.id
 AND i.identity_type = 'DEVICE_SN'
 AND i.identity_scope = 'DEVICE'
SET s.source_record_id = CONCAT(
  'QUALITY-',
  LEFT(SHA2(CONCAT(
    LOWER(REGEXP_REPLACE(TRIM(q.return_order_no), '[[:space:]]+', ' ')),
    CHAR(31),
    LOWER(HEX(i.fingerprint))
  ), 256), 32)
)
WHERE s.source_channel = 'QUALITY'
  AND q.return_order_no IS NOT NULL
  AND TRIM(q.return_order_no) <> '';

UPDATE t_import_row r
JOIN t_feedback_source s ON s.id = r.feedback_source_id
SET r.business_key_hash = UNHEX(SHA2(CONCAT('QUALITY:', s.source_record_id), 256))
WHERE s.source_channel = 'QUALITY';
