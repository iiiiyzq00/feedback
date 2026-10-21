-- Keep encrypted import attachment references in the import-domain cold row store.
ALTER TABLE t_import_row
  DROP CHECK chk_import_record_type,
  ADD COLUMN legacy_source_table VARCHAR(50) COLLATE utf8mb4_0900_as_cs NULL,
  ADD COLUMN legacy_source_id BIGINT NULL,
  ADD COLUMN parent_import_row_id BIGINT NULL,
  ADD COLUMN feedback_attachment_id BIGINT NULL,
  ADD COLUMN source_url_hash BINARY(32) NULL,
  ADD COLUMN url_domain VARCHAR(255) COLLATE utf8mb4_0900_as_cs NULL,
  ADD COLUMN attachment_type VARCHAR(20) COLLATE utf8mb4_0900_as_cs NULL,
  ADD COLUMN expected_sha256 BINARY(32) NULL,
  ADD COLUMN expected_size_bytes BIGINT NULL,
  ADD COLUMN task_status VARCHAR(20) COLLATE utf8mb4_0900_as_cs NULL,
  ADD COLUMN final_status VARCHAR(20) COLLATE utf8mb4_0900_as_cs NULL,
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
  ADD COLUMN next_retry_at DATETIME(3) NULL,
  ADD COLUMN last_error VARCHAR(500) COLLATE utf8mb4_0900_as_cs NULL,
  ADD CONSTRAINT chk_import_record_type CHECK (record_type IN ('SHEET','ROW','QUALITY_EVENT','ATTACHMENT_TASK')),
  ADD CONSTRAINT chk_import_attachment_task_required CHECK (
    record_type<>'ATTACHMENT_TASK' OR
    (parent_import_row_id IS NOT NULL AND source_url_hash IS NOT NULL AND url_domain IS NOT NULL
      AND attachment_type IN ('IMAGE','VIDEO') AND task_status IS NOT NULL)
  ),
  ADD CONSTRAINT chk_import_compact_attachment_size CHECK (
    expected_size_bytes IS NULL OR expected_size_bytes BETWEEN 1 AND 209715200
  ),
  ADD CONSTRAINT chk_import_compact_retry CHECK (retry_count>=0),
  ADD UNIQUE KEY uk_import_row_legacy_source(legacy_source_table,legacy_source_id),
  ADD UNIQUE KEY uk_import_attachment_reference(parent_import_row_id,source_url_hash),
  ADD KEY idx_import_attachment_worker(record_type,task_status,retry_count,created_at),
  ADD KEY idx_import_attachment_entity(feedback_attachment_id);

INSERT INTO t_import_row(
  batch_id,record_type,legacy_source_table,legacy_source_id,parent_import_row_id,feedback_attachment_id,
  raw_row_content_enc,raw_nonce,raw_tag,raw_key_id,raw_algorithm,raw_retention_until,
  source_url_hash,url_domain,attachment_type,expected_sha256,expected_size_bytes,
  task_status,final_status,retry_count,next_retry_at,last_error,created_at,updated_at
)
SELECT r.batch_id,'ATTACHMENT_TASK','t_import_attachment_task',t.id,t.import_row_id,t.feedback_attachment_id,
  t.source_url_ciphertext,t.source_url_nonce,t.source_url_tag,t.source_url_key_id,t.source_url_algorithm,t.url_expires_at,
  t.source_url_hash,t.url_domain,t.attachment_type,t.expected_sha256,t.expected_size_bytes,
  t.task_status,t.final_status,t.retry_count,t.next_retry_at,t.last_error,t.created_at,t.updated_at
FROM t_import_attachment_task t JOIN t_import_row r ON r.id=t.import_row_id
ON DUPLICATE KEY UPDATE
  feedback_attachment_id=VALUES(feedback_attachment_id),raw_row_content_enc=VALUES(raw_row_content_enc),
  raw_nonce=VALUES(raw_nonce),raw_tag=VALUES(raw_tag),raw_key_id=VALUES(raw_key_id),
  raw_algorithm=VALUES(raw_algorithm),raw_retention_until=VALUES(raw_retention_until),
  source_url_hash=VALUES(source_url_hash),url_domain=VALUES(url_domain),attachment_type=VALUES(attachment_type),
  expected_sha256=VALUES(expected_sha256),expected_size_bytes=VALUES(expected_size_bytes),
  task_status=VALUES(task_status),final_status=VALUES(final_status),retry_count=VALUES(retry_count),
  next_retry_at=VALUES(next_retry_at),last_error=VALUES(last_error),updated_at=VALUES(updated_at);

CREATE TRIGGER trg_import_attachment_row_insert
AFTER INSERT ON t_import_attachment_task FOR EACH ROW
INSERT INTO t_import_row(
  batch_id,record_type,legacy_source_table,legacy_source_id,parent_import_row_id,feedback_attachment_id,
  raw_row_content_enc,raw_nonce,raw_tag,raw_key_id,raw_algorithm,raw_retention_until,
  source_url_hash,url_domain,attachment_type,expected_sha256,expected_size_bytes,
  task_status,final_status,retry_count,next_retry_at,last_error,created_at,updated_at
)
SELECT r.batch_id,'ATTACHMENT_TASK','t_import_attachment_task',NEW.id,NEW.import_row_id,NEW.feedback_attachment_id,
  NEW.source_url_ciphertext,NEW.source_url_nonce,NEW.source_url_tag,NEW.source_url_key_id,NEW.source_url_algorithm,NEW.url_expires_at,
  NEW.source_url_hash,NEW.url_domain,NEW.attachment_type,NEW.expected_sha256,NEW.expected_size_bytes,
  NEW.task_status,NEW.final_status,NEW.retry_count,NEW.next_retry_at,NEW.last_error,NEW.created_at,NEW.updated_at
FROM t_import_row r WHERE r.id=NEW.import_row_id;

CREATE TRIGGER trg_import_attachment_row_update
AFTER UPDATE ON t_import_attachment_task FOR EACH ROW
UPDATE t_import_row c JOIN t_import_row r ON r.id=NEW.import_row_id SET
  c.batch_id=r.batch_id,c.parent_import_row_id=NEW.import_row_id,c.feedback_attachment_id=NEW.feedback_attachment_id,
  c.raw_row_content_enc=NEW.source_url_ciphertext,c.raw_nonce=NEW.source_url_nonce,c.raw_tag=NEW.source_url_tag,
  c.raw_key_id=NEW.source_url_key_id,c.raw_algorithm=NEW.source_url_algorithm,c.raw_retention_until=NEW.url_expires_at,
  c.source_url_hash=NEW.source_url_hash,c.url_domain=NEW.url_domain,c.attachment_type=NEW.attachment_type,
  c.expected_sha256=NEW.expected_sha256,c.expected_size_bytes=NEW.expected_size_bytes,
  c.task_status=NEW.task_status,c.final_status=NEW.final_status,c.retry_count=NEW.retry_count,
  c.next_retry_at=NEW.next_retry_at,c.last_error=NEW.last_error,c.updated_at=NEW.updated_at
WHERE c.legacy_source_table='t_import_attachment_task' AND c.legacy_source_id=OLD.id;

CREATE TRIGGER trg_import_attachment_row_delete
AFTER DELETE ON t_import_attachment_task FOR EACH ROW
DELETE FROM t_import_row
WHERE legacy_source_table='t_import_attachment_task' AND legacy_source_id=OLD.id;
