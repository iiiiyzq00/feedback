ALTER TABLE t_import_template
  ADD COLUMN active_channel_key VARCHAR(20)
    GENERATED ALWAYS AS (CASE WHEN is_active THEN source_channel ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_active_template_channel(active_channel_key);
