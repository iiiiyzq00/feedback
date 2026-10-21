ALTER TABLE t_ai_knowledge
  ADD COLUMN source_feedback_id BIGINT NULL AFTER source_rca_id,
  ADD COLUMN candidate_fingerprint BINARY(32) NULL AFTER source_feedback_id,
  ADD COLUMN candidate_score DECIMAL(5,4) NULL AFTER candidate_fingerprint,
  ADD COLUMN candidate_evidence JSON NULL AFTER candidate_score,
  ADD UNIQUE KEY uk_knowledge_source_feedback (source_feedback_id,knowledge_source),
  ADD UNIQUE KEY uk_knowledge_candidate_fingerprint (candidate_fingerprint);

INSERT INTO t_auth_permission(permission_code,permission_name,permission_type,parent_code,resource_path,sort_order)
VALUES('action:generate_jira_knowledge_candidates','生成Jira知识候选草稿','BUTTON','KNOWLEDGE','POST /api/v1/admin/knowledge/jira-candidates/generate',655)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_path=VALUES(resource_path),sort_order=VALUES(sort_order);

INSERT IGNORE INTO t_auth_role_permission(role_code,permission_code) VALUES
('ADMIN','action:generate_jira_knowledge_candidates'),('OPERATOR','action:generate_jira_knowledge_candidates');
