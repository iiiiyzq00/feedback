-- shadow-11 全量候选只写入隔离 generation；本迁移不创建、不验证、更不激活任何 generation。
CREATE TABLE t_cluster_shadow_generation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  generation_key VARCHAR(100) NOT NULL,
  generation_status VARCHAR(20) NOT NULL DEFAULT 'BUILDING',
  algorithm_version VARCHAR(100) NOT NULL,
  model_version VARCHAR(100) NOT NULL,
  build_fence_source_id BIGINT NOT NULL,
  expected_source_count BIGINT NOT NULL,
  processed_count BIGINT NOT NULL DEFAULT 0,
  actionable_count BIGINT NOT NULL DEFAULT 0,
  terminal_count BIGINT NOT NULL DEFAULT 0,
  cluster_count BIGINT NOT NULL DEFAULT 0,
  provisional_count BIGINT NOT NULL DEFAULT 0,
  input_digest CHAR(64) DEFAULT NULL,
  assignment_digest CHAR(64) DEFAULT NULL,
  reverse_assignment_digest CHAR(64) DEFAULT NULL,
  verification_digest CHAR(64) DEFAULT NULL,
  failure_code VARCHAR(100) DEFAULT NULL,
  failure_summary VARCHAR(500) DEFAULT NULL,
  created_by BIGINT NOT NULL,
  verified_by BIGINT DEFAULT NULL,
  verified_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  building_slot TINYINT GENERATED ALWAYS AS
    (CASE WHEN generation_status='BUILDING' THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_cluster_shadow_generation_key (generation_key),
  UNIQUE KEY uk_cluster_shadow_single_building (building_slot),
  KEY idx_cluster_shadow_generation_status (generation_status,created_at,id),
  CONSTRAINT chk_cluster_shadow_generation_status CHECK
    (generation_status IN ('BUILDING','VERIFIED','FAILED')),
  CONSTRAINT chk_cluster_shadow_generation_counts CHECK
    (build_fence_source_id >= 0 AND expected_source_count >= 0 AND processed_count >= 0 AND
     actionable_count >= 0 AND terminal_count >= 0 AND cluster_count >= 0 AND provisional_count >= 0),
  CONSTRAINT chk_cluster_shadow_generation_digest CHECK
    ((input_digest IS NULL OR input_digest REGEXP '^[0-9a-f]{64}$') AND
     (assignment_digest IS NULL OR assignment_digest REGEXP '^[0-9a-f]{64}$') AND
     (reverse_assignment_digest IS NULL OR reverse_assignment_digest REGEXP '^[0-9a-f]{64}$') AND
     (verification_digest IS NULL OR verification_digest REGEXP '^[0-9a-f]{64}$')),
  CONSTRAINT chk_cluster_shadow_generation_terminal_state CHECK
    ((generation_status='BUILDING') OR
     (generation_status='FAILED' AND failure_code IS NOT NULL AND failure_summary IS NOT NULL) OR
     (generation_status='VERIFIED' AND verified_by IS NOT NULL AND verified_at IS NOT NULL AND
      input_digest IS NOT NULL AND assignment_digest IS NOT NULL AND
      reverse_assignment_digest=assignment_digest AND verification_digest IS NOT NULL AND
      failure_code IS NULL AND failure_summary IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='shadow-11 隔离全量候选 generation；无 ACTIVE 状态';

CREATE TABLE t_cluster_shadow_candidate_cluster (
  generation_id BIGINT NOT NULL,
  cluster_key_hash CHAR(64) NOT NULL,
  cluster_name_hash CHAR(64) NOT NULL,
  member_count BIGINT NOT NULL,
  provisional_member_count BIGINT NOT NULL,
  manual_member_count BIGINT NOT NULL,
  PRIMARY KEY (generation_id,cluster_key_hash),
  CONSTRAINT fk_cluster_shadow_candidate_cluster_generation FOREIGN KEY (generation_id)
    REFERENCES t_cluster_shadow_generation(id) ON DELETE CASCADE,
  CONSTRAINT chk_cluster_shadow_candidate_cluster_counts CHECK
    (member_count > 0 AND provisional_member_count >= 0 AND manual_member_count >= 0 AND
     provisional_member_count <= member_count AND manual_member_count <= member_count),
  CONSTRAINT chk_cluster_shadow_candidate_cluster_hashes CHECK
    (cluster_key_hash REGEXP '^[0-9a-f]{64}$' AND cluster_name_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='隔离 generation 的候选症状簇汇总，不保存正文或展示名称';

CREATE TABLE t_cluster_shadow_candidate_member (
  generation_id BIGINT NOT NULL,
  feedback_source_id BIGINT NOT NULL,
  problem_status VARCHAR(50) NOT NULL,
  cluster_key_hash CHAR(64) DEFAULT NULL,
  decision_code VARCHAR(50) NOT NULL,
  score DECIMAL(7,6) DEFAULT NULL,
  manual_locked TINYINT(1) NOT NULL DEFAULT 0,
  application_hash CHAR(64) DEFAULT NULL,
  failure_mode_hash CHAR(64) DEFAULT NULL,
  target_hash CHAR(64) DEFAULT NULL,
  action_hash CHAR(64) DEFAULT NULL,
  error_signature_hash CHAR(64) DEFAULT NULL,
  PRIMARY KEY (generation_id,feedback_source_id),
  KEY idx_cluster_shadow_candidate_member_cluster (generation_id,cluster_key_hash,feedback_source_id),
  CONSTRAINT fk_cluster_shadow_candidate_member_generation FOREIGN KEY (generation_id)
    REFERENCES t_cluster_shadow_generation(id) ON DELETE CASCADE,
  CONSTRAINT chk_cluster_shadow_candidate_member_score CHECK (score IS NULL OR (score >= 0 AND score <= 1)),
  CONSTRAINT chk_cluster_shadow_candidate_member_decision CHECK
    (decision_code IN ('NEW_CLUSTER','LINK_EXISTING','PROVISIONAL_NEW_CLUSTER',
      'PROVISIONAL_LINK_EXISTING','MANUAL_LOCKED','TERMINAL')),
  CONSTRAINT chk_cluster_shadow_candidate_member_shape CHECK
    (manual_locked IN (0,1) AND
      ((manual_locked=1 AND cluster_key_hash IS NOT NULL) OR
       (manual_locked=0 AND
        ((problem_status='ACTIONABLE' AND cluster_key_hash IS NOT NULL) OR
         (problem_status<>'ACTIONABLE' AND cluster_key_hash IS NULL))))),
  CONSTRAINT chk_cluster_shadow_candidate_member_hashes CHECK
    ((cluster_key_hash IS NULL OR cluster_key_hash REGEXP '^[0-9a-f]{64}$') AND
     (application_hash IS NULL OR application_hash REGEXP '^[0-9a-f]{64}$') AND
     (failure_mode_hash IS NULL OR failure_mode_hash REGEXP '^[0-9a-f]{64}$') AND
     (target_hash IS NULL OR target_hash REGEXP '^[0-9a-f]{64}$') AND
     (action_hash IS NULL OR action_hash REGEXP '^[0-9a-f]{64}$') AND
     (error_signature_hash IS NULL OR error_signature_hash REGEXP '^[0-9a-f]{64}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='隔离 generation 的候选成员及不可逆边界摘要';
