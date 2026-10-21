ALTER TABLE t_channel_redblack
  ADD COLUMN original_id_raw VARCHAR(100) DEFAULT NULL AFTER original_id;
