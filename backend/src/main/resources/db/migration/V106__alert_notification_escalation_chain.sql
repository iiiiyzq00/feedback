CREATE TABLE t_alert_escalation_chain (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  alert_instance_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  rule_code VARCHAR(50) NOT NULL,
  incident_id BIGINT DEFAULT NULL,
  owner_team VARCHAR(50) DEFAULT NULL,
  severity VARCHAR(5) NOT NULL,
  chain_status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  current_target_order INT NOT NULL DEFAULT 1,
  current_channel_order INT NOT NULL DEFAULT 1,
  confirmation_deadline DATETIME(3) DEFAULT NULL,
  acknowledged_by BIGINT DEFAULT NULL,
  acknowledged_at DATETIME(3) DEFAULT NULL,
  last_failure_reason VARCHAR(500) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_escalation_alert (alert_instance_id),
  KEY idx_escalation_due (chain_status,confirmation_deadline),
  CONSTRAINT chk_escalation_chain_status CHECK(chain_status IN ('RUNNING','ACKNOWLEDGED','EXHAUSTED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='告警逐人逐渠道升级链';

CREATE TABLE t_alert_escalation_target (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  chain_id BIGINT NOT NULL,
  target_order INT NOT NULL,
  target_type VARCHAR(20) NOT NULL,
  user_id BIGINT DEFAULT NULL,
  display_name_snapshot VARCHAR(100) NOT NULL,
  channels JSON NOT NULL,
  target_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_escalation_target_order (chain_id,target_order),
  KEY idx_escalation_target_user (user_id),
  CONSTRAINT fk_escalation_target_chain FOREIGN KEY(chain_id) REFERENCES t_alert_escalation_chain(id),
  CONSTRAINT chk_escalation_target_type CHECK(target_type IN ('PRIMARY','BACKUP','LEADER','PUBLIC')),
  CONSTRAINT chk_escalation_target_status CHECK(target_status IN ('PENDING','IN_PROGRESS','ACKNOWLEDGED','FAILED','SKIPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='告警升级链责任目标';

ALTER TABLE t_alert_notification
  ADD COLUMN escalation_chain_id BIGINT DEFAULT NULL AFTER alert_instance_id,
  ADD COLUMN escalation_target_id BIGINT DEFAULT NULL AFTER escalation_chain_id,
  ADD COLUMN target_user_id BIGINT DEFAULT NULL AFTER escalation_target_id,
  ADD COLUMN requires_ack BOOLEAN NOT NULL DEFAULT FALSE AFTER target_user_id,
  ADD COLUMN ack_deadline DATETIME(3) DEFAULT NULL AFTER requires_ack,
  ADD COLUMN acknowledged_at DATETIME(3) DEFAULT NULL AFTER ack_deadline,
  ADD COLUMN acknowledgement_type VARCHAR(30) DEFAULT NULL AFTER acknowledged_at,
  ADD COLUMN channel_sequence INT DEFAULT NULL AFTER acknowledgement_type,
  ADD KEY idx_notification_escalation (escalation_chain_id,escalation_target_id,channel_sequence),
  ADD CONSTRAINT fk_notification_escalation_chain FOREIGN KEY(escalation_chain_id) REFERENCES t_alert_escalation_chain(id),
  ADD CONSTRAINT fk_notification_escalation_target FOREIGN KEY(escalation_target_id) REFERENCES t_alert_escalation_target(id);
