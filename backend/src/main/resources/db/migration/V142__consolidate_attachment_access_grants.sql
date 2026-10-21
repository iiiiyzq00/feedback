-- Consolidate mutable one-time attachment grants into the retained authorization vault.
ALTER TABLE t_authorized_contact_vault
  DROP CHECK chk_vault_authorized_cipher,
  DROP CHECK chk_vault_contact_type,
  ADD COLUMN attachment_id BIGINT NULL,
  ADD COLUMN user_id BIGINT NULL,
  ADD COLUMN access_reason VARCHAR(200) COLLATE utf8mb4_0900_as_cs NULL,
  ADD COLUMN consumed_at DATETIME(3) NULL,
  ADD COLUMN revoked_at DATETIME(3) NULL,
  ADD COLUMN legacy_source_id BIGINT NULL,
  ADD CONSTRAINT chk_vault_contact_type CHECK (contact_type IN ('PHONE','EMAIL','ATTACHMENT_GRANT')),
  ADD CONSTRAINT chk_vault_record_shape CHECK (
    (contact_type='ATTACHMENT_GRANT' AND attachment_id IS NOT NULL AND user_id IS NOT NULL
      AND CHAR_LENGTH(access_reason) BETWEEN 3 AND 200 AND is_authorized=TRUE
      AND contact_ciphertext IS NULL AND contact_nonce IS NULL AND contact_tag IS NULL
      AND contact_key_id IS NULL AND contact_algorithm IS NULL AND authorization_basis IS NULL AND authorized_at IS NULL)
    OR
    (contact_type IN ('PHONE','EMAIL') AND attachment_id IS NULL AND user_id IS NULL
      AND access_reason IS NULL AND consumed_at IS NULL AND revoked_at IS NULL AND legacy_source_id IS NULL
      AND (((is_authorized=FALSE) AND contact_ciphertext IS NULL AND contact_nonce IS NULL AND contact_tag IS NULL
          AND contact_key_id IS NULL AND contact_algorithm IS NULL AND authorization_basis IS NULL AND authorized_at IS NULL)
        OR ((is_authorized=TRUE) AND contact_ciphertext IS NOT NULL AND contact_nonce IS NOT NULL AND contact_tag IS NOT NULL
          AND contact_key_id IS NOT NULL AND contact_algorithm IS NOT NULL
          AND authorization_basis='SDK_CONSENT' AND authorized_at IS NOT NULL)))
  ),
  ADD UNIQUE KEY uk_vault_attachment_grant_legacy(legacy_source_id),
  ADD KEY idx_vault_attachment_grant_lookup(contact_type,fingerprint,attachment_id,source_id,user_id,expires_at),
  ADD KEY idx_vault_attachment_grant_expiry(contact_type,expires_at,consumed_at,revoked_at);

INSERT INTO t_authorized_contact_vault(
  ingest_id,source_id,contact_type,fingerprint,fingerprint_key_id,is_authorized,expires_at,
  attachment_id,user_id,access_reason,consumed_at,revoked_at,legacy_source_id,created_at,updated_at
)
SELECT attachment_id,source_id,'ATTACHMENT_GRANT',grant_hash,'SHA-256',TRUE,expires_at,
  attachment_id,user_id,reason,consumed_at,revoked_at,id,created_at,created_at
FROM t_attachment_access_grant
ON DUPLICATE KEY UPDATE source_id=VALUES(source_id),fingerprint=VALUES(fingerprint),expires_at=VALUES(expires_at),
  attachment_id=VALUES(attachment_id),user_id=VALUES(user_id),access_reason=VALUES(access_reason),
  consumed_at=VALUES(consumed_at),revoked_at=VALUES(revoked_at),updated_at=VALUES(updated_at);

CREATE TRIGGER trg_attachment_grant_vault_insert
AFTER INSERT ON t_attachment_access_grant FOR EACH ROW
INSERT INTO t_authorized_contact_vault(
  ingest_id,source_id,contact_type,fingerprint,fingerprint_key_id,is_authorized,expires_at,
  attachment_id,user_id,access_reason,consumed_at,revoked_at,legacy_source_id,created_at,updated_at
)
VALUES(NEW.attachment_id,NEW.source_id,'ATTACHMENT_GRANT',NEW.grant_hash,'SHA-256',TRUE,NEW.expires_at,
  NEW.attachment_id,NEW.user_id,NEW.reason,NEW.consumed_at,NEW.revoked_at,NEW.id,NEW.created_at,NEW.created_at);

CREATE TRIGGER trg_attachment_grant_vault_update
AFTER UPDATE ON t_attachment_access_grant FOR EACH ROW
UPDATE t_authorized_contact_vault SET ingest_id=NEW.attachment_id,source_id=NEW.source_id,
  fingerprint=NEW.grant_hash,expires_at=NEW.expires_at,attachment_id=NEW.attachment_id,user_id=NEW.user_id,
  access_reason=NEW.reason,consumed_at=NEW.consumed_at,revoked_at=NEW.revoked_at,updated_at=NOW(3)
WHERE contact_type='ATTACHMENT_GRANT' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_attachment_grant_vault_delete
AFTER DELETE ON t_attachment_access_grant FOR EACH ROW
DELETE FROM t_authorized_contact_vault
WHERE contact_type='ATTACHMENT_GRANT' AND legacy_source_id=OLD.id;
