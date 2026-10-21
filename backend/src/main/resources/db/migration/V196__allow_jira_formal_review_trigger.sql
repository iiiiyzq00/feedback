-- Forward-only extension for the controlled re-pull of Jira records that were
-- downgraded by a later outbound privacy gate.
ALTER TABLE t_import_batch
  DROP CHECK chk_import_trigger_type,
  ADD CONSTRAINT chk_import_trigger_type CHECK (
    trigger_type IS NULL OR trigger_type IN ('MANUAL','SCHEDULED','BACKFILL','FORMAL_REVIEW')
  );
