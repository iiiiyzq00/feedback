-- 分发通知的投递确认、整批补偿和补偿重试事实。
-- 既有交接均是站内通知，前向标记为已投递；不改写 V190～V211。

CREATE TABLE t_feedback_distribution_batch (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id VARCHAR(100) NOT NULL,
  initiated_by BIGINT NOT NULL,
  target_user_id BIGINT NOT NULL,
  item_count INT NOT NULL,
  batch_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  failure_reason VARCHAR(1000) DEFAULT NULL,
  failed_task_id BIGINT DEFAULT NULL,
  compensation_attempts INT NOT NULL DEFAULT 0,
  next_compensation_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  compensated_at DATETIME(3) DEFAULT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_feedback_distribution_batch_request(request_id),
  KEY idx_feedback_distribution_compensation(batch_status,next_compensation_at,id),
  CONSTRAINT fk_feedback_distribution_batch_actor FOREIGN KEY(initiated_by) REFERENCES t_auth_user(id),
  CONSTRAINT fk_feedback_distribution_batch_target FOREIGN KEY(target_user_id) REFERENCES t_auth_user(id),
  CONSTRAINT chk_feedback_distribution_batch_count CHECK(item_count BETWEEN 1 AND 100),
  CONSTRAINT chk_feedback_distribution_batch_status CHECK(batch_status IN ('ACTIVE','DELIVERED','COMPENSATING','COMPENSATED','COMPENSATION_FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='分发批次及整批通知补偿事实';

ALTER TABLE t_feedback_handoff_task
  ADD COLUMN distribution_batch_id BIGINT DEFAULT NULL AFTER id,
  ADD COLUMN provider_idempotency_key VARCHAR(140) DEFAULT NULL AFTER notification_id,
  ADD COLUMN delivery_status VARCHAR(30) NOT NULL DEFAULT 'DELIVERED' AFTER provider_idempotency_key,
  ADD COLUMN provider_receipt_id VARCHAR(255) DEFAULT NULL AFTER delivery_status,
  ADD COLUMN delivery_failure_reason VARCHAR(1000) DEFAULT NULL AFTER provider_receipt_id,
  ADD COLUMN delivery_confirmed_at DATETIME(3) DEFAULT NULL AFTER delivery_failure_reason,
  ADD COLUMN compensation_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED' AFTER delivery_confirmed_at,
  ADD COLUMN compensated_at DATETIME(3) DEFAULT NULL AFTER compensation_status,
  ADD UNIQUE KEY uk_feedback_handoff_provider_key(provider_idempotency_key),
  ADD KEY idx_feedback_handoff_batch(distribution_batch_id,id),
  ADD KEY idx_feedback_handoff_delivery(delivery_status,created_at,id),
  ADD CONSTRAINT fk_feedback_handoff_batch FOREIGN KEY(distribution_batch_id) REFERENCES t_feedback_distribution_batch(id),
  ADD CONSTRAINT chk_feedback_handoff_delivery CHECK(delivery_status IN ('PENDING','DELIVERED','UNKNOWN','FAILED')),
  ADD CONSTRAINT chk_feedback_handoff_compensation CHECK(compensation_status IN ('NOT_REQUIRED','PENDING','COMPLETED','FAILED'));

UPDATE t_feedback_handoff_task
SET provider_idempotency_key=CONCAT('legacy-handoff:',id),
    delivery_status='DELIVERED',
    delivery_confirmed_at=COALESCE(created_at,NOW(3))
WHERE provider_idempotency_key IS NULL;

ALTER TABLE t_feedback_handoff_task
  MODIFY COLUMN provider_idempotency_key VARCHAR(140) NOT NULL;

ALTER TABLE t_feedback_handoff_task DROP CHECK chk_feedback_handoff_status;
ALTER TABLE t_feedback_handoff_task
  ADD CONSTRAINT chk_feedback_handoff_status CHECK(task_status IN ('DELIVERED','ACCEPTED','CANCELLED','ROLLED_BACK'));

ALTER TABLE t_feedback_disposition_event DROP CHECK chk_feedback_disposition_type;
ALTER TABLE t_feedback_disposition_event
  ADD CONSTRAINT chk_feedback_disposition_type CHECK(event_type IN (
    'CLAIMED','HANDOFF','HANDOFF_ACCEPTED','HANDOFF_CANCELLED','HANDOFF_ROLLED_BACK',
    'PROCESSED','STATUS_CHANGED','REOPENED'
  ));
