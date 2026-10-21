ALTER TABLE t_ai_task DROP CHECK chk_ai_task_record_type;
ALTER TABLE t_ai_task ADD CONSTRAINT chk_ai_task_record_type CHECK (
  record_type IN ('SKILL_DEF','PROMPT_VERSION','INVOCATION','ASSISTANT_QUERY','PROMPT_EVALUATION','INDEX_OUTBOX','CLUSTER_EVALUATION')
);
ALTER TABLE t_ai_task ADD KEY idx_cluster_evaluation_gate (record_type,task_status,completed_at);
