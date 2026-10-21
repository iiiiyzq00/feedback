-- Standalone universal feedback app trigger taxonomy. Shake triggering is removed.
UPDATE t_channel_sdk SET trigger_type = 'API_CALL' WHERE trigger_type = 'SHAKE';
ALTER TABLE t_channel_sdk DROP CHECK chk_sdk_trigger_type;
ALTER TABLE t_channel_sdk ADD CONSTRAINT chk_sdk_trigger_type CHECK (
  trigger_type IS NULL OR trigger_type IN (
    'FLOAT_BUTTON','NOTIFICATION','LAUNCHER','DEEPLINK','QUICK_SETTINGS','PAGE_ENTRY','API_CALL'
  )
);
