-- Topic 13: explicit online window for searchable knowledge.
ALTER TABLE t_ai_knowledge
  ADD COLUMN expires_at DATETIME(3) DEFAULT NULL AFTER published_at,
  ADD KEY idx_knowledge_online_window (knowledge_status,expires_at,feature_module,issue_type),
  ADD CONSTRAINT chk_knowledge_expiry CHECK (expires_at IS NULL OR expires_at > created_at);
