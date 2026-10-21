DROP TRIGGER IF EXISTS trg_import_upload_chunk_compact_session;

CREATE TRIGGER trg_import_upload_chunk_compact_session
BEFORE INSERT ON t_import_upload_chunk FOR EACH ROW
SET NEW.compact_session_id=COALESCE(
  NEW.compact_session_id,
  (SELECT id FROM t_import_row
   WHERE record_type='UPLOAD_SESSION' AND legacy_source_id=NEW.session_id LIMIT 1)
);
