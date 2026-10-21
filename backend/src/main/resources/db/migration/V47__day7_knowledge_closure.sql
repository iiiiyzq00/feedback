-- D7-K01..K12: governed knowledge revisions, review evidence and usage feedback.
ALTER TABLE t_ai_knowledge
  ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0 AFTER knowledge_status,
  ADD COLUMN supersedes_knowledge_id BIGINT DEFAULT NULL AFTER source_rca_id,
  ADD COLUMN feature_module VARCHAR(50) DEFAULT NULL AFTER knowledge_tags,
  ADD COLUMN issue_type VARCHAR(50) DEFAULT NULL AFTER feature_module,
  ADD COLUMN shown_count BIGINT NOT NULL DEFAULT 0 AFTER adoption_count,
  ADD COLUMN effective_count BIGINT NOT NULL DEFAULT 0 AFTER shown_count,
  ADD COLUMN ineffective_count BIGINT NOT NULL DEFAULT 0 AFTER effective_count,
  ADD COLUMN effectiveness_score DECIMAL(8,6) DEFAULT NULL AFTER ineffective_count,
  ADD COLUMN reviewed_by BIGINT DEFAULT NULL AFTER created_by,
  ADD COLUMN reviewed_at DATETIME(3) DEFAULT NULL AFTER reviewed_by,
  ADD COLUMN review_comment VARCHAR(500) DEFAULT NULL AFTER reviewed_at,
  ADD COLUMN published_at DATETIME(3) DEFAULT NULL AFTER review_comment,
  ADD COLUMN rejected_at DATETIME(3) DEFAULT NULL AFTER published_at,
  ADD COLUMN deprecated_at DATETIME(3) DEFAULT NULL AFTER rejected_at,
  ADD COLUMN last_used_at DATETIME(3) DEFAULT NULL AFTER deprecated_at,
  ADD KEY idx_knowledge_recommendation (knowledge_status,feature_module,issue_type,effectiveness_score),
  ADD KEY idx_knowledge_supersedes (supersedes_knowledge_id);

UPDATE t_ai_knowledge
SET feature_module=JSON_UNQUOTE(JSON_EXTRACT(knowledge_tags,'$[0]'))
WHERE feature_module IS NULL AND JSON_VALID(knowledge_tags) AND JSON_LENGTH(knowledge_tags)>0;

UPDATE t_ai_knowledge SET reviewed_by=COALESCE(created_by,0),reviewed_at=updated_at,review_comment='V47历史状态迁移',published_at=updated_at WHERE knowledge_status='PUBLISHED';
UPDATE t_ai_knowledge SET reviewed_by=COALESCE(created_by,0),reviewed_at=updated_at,review_comment='V47历史状态迁移',rejected_at=updated_at WHERE knowledge_status='REJECTED';
UPDATE t_ai_knowledge SET deprecated_at=updated_at WHERE knowledge_status='DEPRECATED';

ALTER TABLE t_ai_knowledge
  ADD CONSTRAINT chk_knowledge_lifecycle_version CHECK (lifecycle_version >= 0),
  ADD CONSTRAINT chk_knowledge_usage_counts CHECK (shown_count>=0 AND adoption_count>=0 AND effective_count>=0 AND ineffective_count>=0),
  ADD CONSTRAINT chk_knowledge_effectiveness CHECK (effectiveness_score IS NULL OR effectiveness_score BETWEEN 0 AND 1),
  ADD CONSTRAINT chk_knowledge_review_evidence CHECK (knowledge_status NOT IN ('PUBLISHED','REJECTED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND review_comment IS NOT NULL)),
  ADD CONSTRAINT chk_knowledge_state_time CHECK ((knowledge_status<>'PUBLISHED' OR published_at IS NOT NULL) AND (knowledge_status<>'REJECTED' OR rejected_at IS NOT NULL) AND (knowledge_status<>'DEPRECATED' OR deprecated_at IS NOT NULL));

CREATE TABLE t_knowledge_usage_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  knowledge_id BIGINT NOT NULL,
  incident_id BIGINT NOT NULL,
  alert_instance_id BIGINT DEFAULT NULL,
  event_type VARCHAR(20) NOT NULL,
  reason VARCHAR(500) DEFAULT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_knowledge_incident_event (knowledge_id,incident_id,event_type),
  KEY idx_knowledge_usage_incident (incident_id,created_at),
  CONSTRAINT chk_knowledge_usage_event CHECK (event_type IN ('SHOWN','ADOPTED','EFFECTIVE','INEFFECTIVE')),
  CONSTRAINT chk_knowledge_feedback_reason CHECK (event_type IN ('SHOWN','ADOPTED') OR reason IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='知识推荐、采纳和有效性反馈事实';
