-- D7-M01..M10: immutable masking releases and exact-match NER allowlist.
ALTER TABLE t_masking_audit
  ADD COLUMN ner_f1_score DECIMAL(7,6) DEFAULT NULL AFTER eval_f1_score,
  ADD COLUMN high_risk_recall DECIMAL(7,6) DEFAULT NULL AFTER ner_f1_score,
  ADD COLUMN false_masking_rate DECIMAL(7,6) DEFAULT NULL AFTER high_risk_recall,
  ADD CONSTRAINT chk_masking_extended_metrics CHECK (
    (ner_f1_score IS NULL OR ner_f1_score BETWEEN 0 AND 1) AND
    (high_risk_recall IS NULL OR high_risk_recall BETWEEN 0 AND 1) AND
    (false_masking_rate IS NULL OR false_masking_rate BETWEEN 0 AND 1));

CREATE TABLE t_masking_rule_release (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  release_version VARCHAR(30) NOT NULL,
  enabled_rules JSON NOT NULL,
  ner_allowlist_version VARCHAR(30) NOT NULL,
  release_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  evaluation_audit_id BIGINT DEFAULT NULL,
  review_reason VARCHAR(500) DEFAULT NULL,
  created_by BIGINT DEFAULT NULL,
  published_by BIGINT DEFAULT NULL,
  published_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_masking_release_version (release_version),
  KEY idx_masking_release_status (release_status,created_at),
  CONSTRAINT chk_masking_release_status CHECK (release_status IN ('DRAFT','EVALUATED','PUBLISHED','REJECTED')),
  CONSTRAINT chk_masking_release_publish CHECK (release_status<>'PUBLISHED' OR (evaluation_audit_id IS NOT NULL AND published_by IS NOT NULL AND published_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='脱敏规则不可变候选和发布版本';

CREATE TABLE t_ner_allowlist (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  term_value VARCHAR(100) NOT NULL,
  term_type VARCHAR(20) NOT NULL,
  term_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  list_version VARCHAR(30) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  lifecycle_version BIGINT NOT NULL DEFAULT 0,
  created_by BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  retired_at DATETIME(3) DEFAULT NULL,
  active_term_key VARCHAR(130) GENERATED ALWAYS AS (CASE WHEN term_status='ACTIVE' THEN CONCAT(term_type,':',term_value) ELSE NULL END) STORED,
  UNIQUE KEY uk_ner_allowlist_active (active_term_key),
  KEY idx_ner_allowlist_version (list_version,term_status),
  CONSTRAINT chk_ner_allowlist_type CHECK (term_type IN ('PRODUCT','FUNCTION','DOMAIN_TERM')),
  CONSTRAINT chk_ner_allowlist_status CHECK (term_status IN ('ACTIVE','RETIRED')),
  CONSTRAINT chk_ner_allowlist_lifecycle CHECK (lifecycle_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='NER精确词条白名单，禁止保存正则和个人信息';

INSERT INTO t_masking_rule_release(release_version,enabled_rules,ner_allowlist_version,release_status,evaluation_audit_id,review_reason,published_by,published_at)
VALUES('local-pii-v3-hanlp',JSON_ARRAY('CREDENTIAL','ID_CARD','BANK_CARD','PHONE','LANDLINE','EMAIL','MAC','IP','ACCOUNT'),'allowlist-v1','PUBLISHED',0,'V48历史基线；无正式标注集证据，不据此声明生产发布就绪',0,NOW(3));
INSERT INTO t_ner_allowlist(term_value,term_type,list_version,reason,created_by) VALUES
('张三学堂','PRODUCT','allowlist-v1','方案3.4产品名误伤保护',0),('AI助教','FUNCTION','allowlist-v1','核心功能名误伤保护',0),('版本','DOMAIN_TERM','allowlist-v1','版本描述通用词',0);
