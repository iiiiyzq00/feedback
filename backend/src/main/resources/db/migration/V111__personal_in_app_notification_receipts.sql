CREATE TABLE t_in_app_notification_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_in_app_notification_user(notification_id,user_id),
  KEY idx_in_app_user_read(user_id,read_at),
  CONSTRAINT fk_in_app_receipt_notification FOREIGN KEY(notification_id) REFERENCES t_alert_notification(id),
  CONSTRAINT fk_in_app_receipt_user FOREIGN KEY(user_id) REFERENCES t_auth_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='站内通知个人已读回执';
