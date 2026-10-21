ALTER TABLE t_ai_knowledge
  ADD COLUMN adoption_count BIGINT NOT NULL DEFAULT 0 AFTER knowledge_status;
