ALTER TABLE t_channel_station_feedback
  ADD COLUMN source_product_name VARCHAR(200) DEFAULT NULL AFTER product_id;
