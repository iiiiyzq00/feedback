ALTER TABLE t_root_cause_analysis
  ADD COLUMN review_draft JSON DEFAULT NULL AFTER review_comment,
  ADD COLUMN review_version BIGINT NOT NULL DEFAULT 0 AFTER review_draft,
  ADD COLUMN validation_status VARCHAR(20) NOT NULL DEFAULT 'PASSED' AFTER review_version,
  ADD COLUMN validation_detail JSON DEFAULT NULL AFTER validation_status,
  ADD COLUMN facts_sha256 CHAR(64) DEFAULT NULL AFTER validation_detail,
  ADD COLUMN feature_module VARCHAR(100) DEFAULT NULL AFTER facts_sha256,
  ADD COLUMN issue_type VARCHAR(100) DEFAULT NULL AFTER feature_module;

ALTER TABLE t_root_cause_analysis
  ADD CONSTRAINT chk_rca_validation_status CHECK (validation_status IN ('PASSED','FAILED','PARTIAL'));

ALTER TABLE t_ai_knowledge
  ADD UNIQUE KEY uk_knowledge_source_rca (source_rca_id, knowledge_source);
