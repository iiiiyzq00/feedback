ALTER TABLE t_feedback_attachment
    ADD COLUMN media_preview_priority INT NOT NULL DEFAULT 0 AFTER media_preview_status,
    ADD COLUMN media_preview_lease_owner VARCHAR(64) NULL AFTER media_preview_next_retry_at,
    ADD KEY idx_attachment_media_preview_priority
        (media_preview_status, media_preview_priority, media_preview_next_retry_at, id);

-- Rows queued by V239 remain low-priority backfill work. New verified uploads are
-- explicitly assigned live priority by the security scanner after this migration.
UPDATE t_feedback_attachment
SET media_preview_priority = 0,
    media_preview_lease_owner = NULL
WHERE media_preview_status IS NOT NULL;
