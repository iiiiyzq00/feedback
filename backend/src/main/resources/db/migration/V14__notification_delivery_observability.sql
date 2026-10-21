ALTER TABLE t_alert_notification
  ADD COLUMN max_retries INT NOT NULL DEFAULT 5 AFTER retry_count,
  ADD COLUMN next_attempt_at DATETIME(3) DEFAULT NULL AFTER max_retries,
  ADD COLUMN last_attempt_at DATETIME(3) DEFAULT NULL AFTER next_attempt_at,
  ADD COLUMN dead_lettered_at DATETIME(3) DEFAULT NULL AFTER last_attempt_at,
  ADD COLUMN dead_letter_status VARCHAR(20) DEFAULT NULL AFTER dead_lettered_at,
  ADD COLUMN provider_message_id VARCHAR(255) DEFAULT NULL AFTER dead_letter_status,
  ADD COLUMN last_http_status SMALLINT DEFAULT NULL AFTER provider_message_id,
  ADD COLUMN last_provider_code VARCHAR(100) DEFAULT NULL AFTER last_http_status,
  ADD COLUMN last_error_type VARCHAR(50) DEFAULT NULL AFTER last_provider_code,
  ADD COLUMN last_error_summary VARCHAR(500) DEFAULT NULL AFTER last_error_type,
  ADD COLUMN manual_replay_count INT NOT NULL DEFAULT 0 AFTER last_error_summary;

ALTER TABLE t_alert_notification DROP CHECK chk_notify_status;
ALTER TABLE t_alert_notification
  ADD CONSTRAINT chk_notify_status CHECK (notify_status IS NULL OR notify_status IN ('PENDING','SENT','DELIVERED','FAILED','DEAD_LETTER')),
  ADD CONSTRAINT chk_notification_max_retries CHECK (max_retries BETWEEN 1 AND 20),
  ADD CONSTRAINT chk_notification_replay_count CHECK (manual_replay_count >= 0),
  ADD CONSTRAINT chk_dead_letter_status CHECK (dead_letter_status IS NULL OR dead_letter_status IN ('OPEN','REPLAYED','IGNORED'));

CREATE INDEX idx_notification_operations
  ON t_alert_notification(record_type, notify_status, next_attempt_at, created_at);

CREATE TABLE t_alert_delivery_attempt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  notify_channel VARCHAR(20) NOT NULL,
  attempt_status VARCHAR(20) NOT NULL COMMENT 'DELIVERED/FAILED',
  started_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NOT NULL,
  duration_ms BIGINT NOT NULL,
  http_status SMALLINT DEFAULT NULL,
  provider_code VARCHAR(100) DEFAULT NULL,
  provider_message_id VARCHAR(255) DEFAULT NULL,
  error_type VARCHAR(50) DEFAULT NULL,
  error_summary VARCHAR(500) DEFAULT NULL COMMENT '脱敏分类摘要，禁止URL/凭证/正文',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_delivery_attempt (notification_id, attempt_no),
  KEY idx_delivery_attempt_window (created_at, attempt_status),
  CONSTRAINT fk_delivery_attempt_notification FOREIGN KEY (notification_id) REFERENCES t_alert_notification(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='告警通知逐次投递证据';

CREATE TABLE t_alert_delivery_evidence (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  evidence_type VARCHAR(30) NOT NULL COMMENT 'SCREENSHOT/PROVIDER_RECEIPT/USER_CONFIRMATION',
  evidence_reference VARCHAR(1000) NOT NULL,
  evidence_sha256 CHAR(64) DEFAULT NULL,
  note VARCHAR(500) DEFAULT NULL,
  created_by BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_delivery_evidence_notification (notification_id, created_at),
  CONSTRAINT fk_delivery_evidence_notification FOREIGN KEY (notification_id) REFERENCES t_alert_notification(id),
  CONSTRAINT chk_delivery_evidence_type CHECK (evidence_type IN ('SCREENSHOT','PROVIDER_RECEIPT','USER_CONFIRMATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='告警通知验收证据索引';

UPDATE t_alert_notification
SET notify_status='DEAD_LETTER', dead_letter_status='OPEN', dead_lettered_at=updated_at,
    last_error_type='LEGACY_RETRY_EXHAUSTED', last_error_summary='历史通知已耗尽重试'
WHERE record_type='NOTIFICATION' AND notify_status='FAILED' AND retry_count>=max_retries;

INSERT INTO t_alert_rule(rule_code,rule_name,category,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled) VALUES
('SYSTEM_NOTIFY_BACKLOG','通知Outbox积压','SYSTEM','v1','ACTIVE','P2',JSON_OBJECT('metric','NOTIFICATION_BACKLOG','threshold',100,'window','5m'),JSON_OBJECT('threshold',50),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'escalation','NONE'),30,1),
('SYSTEM_NOTIFY_FAILURE_RATE','通知投递失败率过高','SYSTEM','v1','ACTIVE','P1',JSON_OBJECT('metric','NOTIFICATION_FAILURE_RATE','threshold',20,'window','5m'),JSON_OBJECT('threshold',10),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'escalation','NONE'),30,1),
('SYSTEM_NOTIFY_OLDEST_PENDING','通知最老待处理消息超时','SYSTEM','v1','ACTIVE','P1',JSON_OBJECT('metric','NOTIFICATION_OLDEST_PENDING','threshold',300,'window','5m'),JSON_OBJECT('threshold',60),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'escalation','NONE'),30,1),
('SYSTEM_NOTIFY_DEAD_LETTER','通知重试耗尽进入死信','SYSTEM','v1','ACTIVE','P1',JSON_OBJECT('metric','NOTIFICATION_DEAD_LETTER','threshold',1,'window','5m'),JSON_OBJECT('threshold',0.5),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'escalation','NONE'),10,1);
