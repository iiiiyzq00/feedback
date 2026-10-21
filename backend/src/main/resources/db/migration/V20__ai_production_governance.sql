-- Forward-only AI production-governance extension.
ALTER TABLE t_ai_task DROP CHECK chk_ai_task_record_type;
ALTER TABLE t_ai_task ADD CONSTRAINT chk_ai_task_record_type CHECK (
  record_type IN ('SKILL_DEF','PROMPT_VERSION','INVOCATION','ASSISTANT_QUERY','PROMPT_EVALUATION')
);

ALTER TABLE t_ai_task
  ADD COLUMN approval_status VARCHAR(20) DEFAULT NULL AFTER lifecycle_status,
  ADD COLUMN approved_by BIGINT DEFAULT NULL AFTER approval_status,
  ADD COLUMN approved_at DATETIME(3) DEFAULT NULL AFTER approved_by,
  ADD COLUMN rollout_percent TINYINT NOT NULL DEFAULT 100 AFTER retired_at,
  ADD COLUMN shadow_percent TINYINT NOT NULL DEFAULT 0 AFTER rollout_percent,
  ADD COLUMN emergency_disabled BOOLEAN NOT NULL DEFAULT FALSE AFTER shadow_percent,
  ADD COLUMN previous_prompt_version_id BIGINT DEFAULT NULL AFTER emergency_disabled,
  ADD COLUMN evaluation_summary JSON DEFAULT NULL AFTER previous_prompt_version_id,
  ADD COLUMN latency_ms BIGINT DEFAULT NULL AFTER application_version,
  ADD COLUMN input_tokens INT DEFAULT NULL AFTER latency_ms,
  ADD COLUMN output_tokens INT DEFAULT NULL AFTER input_tokens,
  ADD COLUMN estimated_cost_usd DECIMAL(12,6) DEFAULT NULL AFTER output_tokens,
  ADD COLUMN provider_request_id VARCHAR(100) DEFAULT NULL AFTER estimated_cost_usd,
  ADD COLUMN model_name_snapshot VARCHAR(100) DEFAULT NULL AFTER provider_request_id,
  ADD COLUMN current_skill_key VARCHAR(50) GENERATED ALWAYS AS (
    CASE WHEN record_type='SKILL_DEF' AND is_current THEN skill_code ELSE NULL END
  ) STORED;

ALTER TABLE t_ai_task
  ADD CONSTRAINT chk_ai_approval CHECK (approval_status IS NULL OR approval_status IN ('PENDING','APPROVED','REJECTED')),
  ADD CONSTRAINT chk_ai_rollout CHECK (rollout_percent BETWEEN 0 AND 100 AND shadow_percent BETWEEN 0 AND 100),
  ADD CONSTRAINT chk_ai_usage CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0)
    AND (latency_ms IS NULL OR latency_ms >= 0) AND (estimated_cost_usd IS NULL OR estimated_cost_usd >= 0)),
  ADD UNIQUE KEY uk_current_skill (current_skill_key),
  ADD KEY idx_ai_prompt_approval (record_type,lifecycle_status,approval_status),
  ADD KEY idx_ai_invocation_metrics (skill_code,prompt_version,created_at,task_status);

UPDATE t_ai_task s
JOIN (
  SELECT skill_code,MAX(id) id FROM t_ai_task
  WHERE record_type='SKILL_DEF' AND lifecycle_status='PUBLISHED' GROUP BY skill_code
) latest ON latest.id=s.id
SET s.is_current=TRUE,s.approval_status='APPROVED',s.approved_at=COALESCE(s.published_at,s.created_at);
