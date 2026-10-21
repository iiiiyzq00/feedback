ALTER TABLE t_sdk_ingest
  DROP CHECK chk_completed_cipher_deleted;

-- COMPLETED now means the encrypted transport body was safely processed, not that it was
-- immediately destroyed. Ciphertext remains governed by retention_until and is removed by
-- CipherCleanupTask. chk_ciphertext_atomic_delete and chk_cipher_deleted_status still enforce
-- atomic deletion and allow deleted envelopes only after completion.
