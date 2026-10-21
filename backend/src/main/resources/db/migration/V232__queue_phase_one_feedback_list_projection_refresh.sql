-- 一期反馈列表投影的可靠脏标记与补偿队列。
-- 本迁移不回填、不创建或激活 generation，也不接入任何现有业务写服务。

CREATE TABLE t_phase_one_feedback_list_projection_dirty (
  source_id BIGINT NOT NULL PRIMARY KEY COMMENT '需要重新投影的反馈来源 ID；不设外键以保留来源删除信号',
  mutation_version BIGINT NOT NULL DEFAULT 1 COMMENT '同一来源每次标脏单调递增，用于并发确认 CAS',
  dirty_reason VARCHAR(30) NOT NULL COMMENT 'SOURCE/CONTENT/AI/WORKFLOW/ASSIGNMENT/HANDOFF/ATTACHMENT/IMPORT/CLUSTER/IDENTITY/CHANNEL',
  task_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RETRY/FAILED',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) DEFAULT NULL,
  lease_owner VARCHAR(80) DEFAULT NULL,
  lease_expires_at DATETIME(3) DEFAULT NULL,
  last_error_code VARCHAR(80) DEFAULT NULL,
  last_error_summary VARCHAR(500) DEFAULT NULL COMMENT '固定清洗摘要，不得保存业务正文、SQL 参数或凭据',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_phase_one_list_projection_dirty_claim
    (task_status,next_retry_at,lease_expires_at,source_id),
  CONSTRAINT chk_phase_one_list_projection_dirty_version CHECK (mutation_version > 0),
  CONSTRAINT chk_phase_one_list_projection_dirty_retry CHECK (retry_count >= 0),
  CONSTRAINT chk_phase_one_list_projection_dirty_reason CHECK
    (dirty_reason IN ('SOURCE','CONTENT','AI','WORKFLOW','ASSIGNMENT','HANDOFF','ATTACHMENT','IMPORT','CLUSTER','IDENTITY','CHANNEL')),
  CONSTRAINT chk_phase_one_list_projection_dirty_status CHECK
    (task_status IN ('PENDING','PROCESSING','RETRY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='一期反馈列表投影可靠脏标记；C1b1 不接业务写入';

-- Governed physical business table count is now 61.
