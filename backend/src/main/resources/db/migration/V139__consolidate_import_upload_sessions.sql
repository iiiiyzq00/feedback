-- Consolidate upload-session metadata without duplicating large encrypted chunk payloads.
ALTER TABLE t_import_row
  DROP CHECK chk_import_record_type,
  MODIFY COLUMN batch_id BIGINT NULL,
  ADD COLUMN upload_file_id BINARY(16) NULL,
  ADD COLUMN source_channel VARCHAR(20) COLLATE utf8mb4_0900_as_cs NULL,
  ADD COLUMN total_chunks INT NULL,
  ADD COLUMN total_size_bytes BIGINT NULL,
  ADD COLUMN upload_status VARCHAR(20) COLLATE utf8mb4_0900_as_cs NULL,
  ADD COLUMN expires_at DATETIME(3) NULL,
  ADD COLUMN created_by BIGINT NULL,
  ADD CONSTRAINT chk_import_record_type CHECK (record_type IN ('SHEET','ROW','QUALITY_EVENT','ATTACHMENT_TASK','UPLOAD_SESSION')),
  ADD CONSTRAINT chk_import_batch_owner CHECK (record_type='UPLOAD_SESSION' OR batch_id IS NOT NULL),
  ADD CONSTRAINT chk_import_upload_session_required CHECK (
    record_type<>'UPLOAD_SESSION' OR
    (batch_id IS NULL AND upload_file_id IS NOT NULL AND source_channel IN ('SDK','JIRA','QUALITY','REDBLACK')
      AND total_chunks BETWEEN 1 AND 1000 AND total_size_bytes>=0
      AND upload_status IN ('UPLOADING','ASSEMBLING','COMPLETED','FAILED','EXPIRED') AND expires_at IS NOT NULL)
  ),
  ADD UNIQUE KEY uk_import_upload_file(upload_file_id),
  ADD KEY idx_import_upload_expiry(record_type,upload_status,expires_at),
  ADD KEY idx_import_upload_legacy(record_type,legacy_source_id);

INSERT INTO t_import_row(
  batch_id,record_type,legacy_source_table,legacy_source_id,upload_file_id,source_channel,
  total_chunks,total_size_bytes,upload_status,expires_at,created_by,created_at,updated_at
)
SELECT NULL,'UPLOAD_SESSION','t_import_upload_session',id,file_id,source_channel,
  total_chunks,total_size_bytes,upload_status,expires_at,created_by,created_at,updated_at
FROM t_import_upload_session
ON DUPLICATE KEY UPDATE source_channel=VALUES(source_channel),total_chunks=VALUES(total_chunks),
  total_size_bytes=VALUES(total_size_bytes),upload_status=VALUES(upload_status),expires_at=VALUES(expires_at),
  created_by=VALUES(created_by),updated_at=VALUES(updated_at);

ALTER TABLE t_import_upload_chunk
  ADD COLUMN compact_session_id BIGINT NULL AFTER session_id,
  ADD KEY idx_import_chunk_compact_session(compact_session_id,chunk_index);

UPDATE t_import_upload_chunk c JOIN t_import_row s
  ON s.record_type='UPLOAD_SESSION' AND s.legacy_source_id=c.session_id
SET c.compact_session_id=s.id;

ALTER TABLE t_import_upload_chunk
  MODIFY COLUMN compact_session_id BIGINT NOT NULL;

CREATE TRIGGER trg_import_upload_session_row_insert
AFTER INSERT ON t_import_upload_session FOR EACH ROW
INSERT INTO t_import_row(
  batch_id,record_type,legacy_source_table,legacy_source_id,upload_file_id,source_channel,
  total_chunks,total_size_bytes,upload_status,expires_at,created_by,created_at,updated_at
)
VALUES(NULL,'UPLOAD_SESSION','t_import_upload_session',NEW.id,NEW.file_id,NEW.source_channel,
  NEW.total_chunks,NEW.total_size_bytes,NEW.upload_status,NEW.expires_at,NEW.created_by,NEW.created_at,NEW.updated_at);

CREATE TRIGGER trg_import_upload_session_row_update
AFTER UPDATE ON t_import_upload_session FOR EACH ROW
UPDATE t_import_row SET upload_file_id=NEW.file_id,source_channel=NEW.source_channel,
  total_chunks=NEW.total_chunks,total_size_bytes=NEW.total_size_bytes,upload_status=NEW.upload_status,
  expires_at=NEW.expires_at,created_by=NEW.created_by,updated_at=NEW.updated_at
WHERE record_type='UPLOAD_SESSION' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_import_upload_session_row_delete
AFTER DELETE ON t_import_upload_session FOR EACH ROW
DELETE FROM t_import_row WHERE record_type='UPLOAD_SESSION' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_import_upload_chunk_compact_session
BEFORE INSERT ON t_import_upload_chunk FOR EACH ROW
SET NEW.compact_session_id=(SELECT id FROM t_import_row
  WHERE record_type='UPLOAD_SESSION' AND legacy_source_id=NEW.session_id LIMIT 1);
