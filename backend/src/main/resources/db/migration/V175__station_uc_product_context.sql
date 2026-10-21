ALTER TABLE t_channel_station_feedback
  ADD COLUMN authenticated_product_code VARCHAR(100) DEFAULT NULL
    COMMENT '经入口校验并用于UC RPC上下文的产品编码'
    AFTER authenticated_user_id;

UPDATE t_channel_station_feedback
SET authenticated_product_code = product_id
WHERE authenticated_product_code IS NULL;

ALTER TABLE t_channel_station_feedback
  MODIFY authenticated_product_code VARCHAR(100) NOT NULL;
