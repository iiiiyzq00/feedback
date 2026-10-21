ALTER TABLE t_feedback_source ADD KEY idx_channel_ingest_time (source_channel, ingest_time);
ALTER TABLE t_feedback_attachment ADD KEY idx_attachment_cleanup (deleted_at, retention_until);
ALTER TABLE t_import_row ADD KEY idx_import_raw_cleanup (raw_deleted_at, raw_retention_until);
ALTER TABLE t_ai_task ADD KEY idx_ai_snapshot_cleanup (snapshot_deleted_at, snapshot_retention_until);
ALTER TABLE t_feedback_identity ADD KEY idx_identity_contact_cleanup (expires_at, contact_ciphertext(32));
ALTER TABLE t_cluster ADD KEY idx_cluster_archive (cluster_status, updated_at);

