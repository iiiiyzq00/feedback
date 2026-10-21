ALTER TABLE t_ai_report
  ADD COLUMN freshness_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' AFTER data_latest_time,
  ADD COLUMN freshness_snapshot JSON DEFAULT NULL AFTER freshness_status,
  ADD KEY idx_report_freshness (freshness_status,generated_at),
  ADD CONSTRAINT chk_report_freshness_status CHECK (freshness_status IN ('UNKNOWN','MET','BREACHED'));

CREATE TABLE t_data_freshness_sla (
  channel VARCHAR(20) PRIMARY KEY,
  latest_ingest_time DATETIME(3) DEFAULT NULL,
  lag_seconds BIGINT DEFAULT NULL,
  threshold_seconds BIGINT NOT NULL,
  sla_status VARCHAR(20) NOT NULL,
  checked_at DATETIME(3) NOT NULL,
  CONSTRAINT chk_freshness_sla_channel CHECK (channel IN ('SDK','JIRA','QUALITY','REDBLACK')),
  CONSTRAINT chk_freshness_sla_status CHECK (sla_status IN ('MET','BREACHED')),
  CONSTRAINT chk_freshness_sla_values CHECK ((lag_seconds IS NULL OR lag_seconds>=0) AND threshold_seconds>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE t_data_freshness_sla_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  channel VARCHAR(20) NOT NULL,
  latest_ingest_time DATETIME(3) DEFAULT NULL,
  lag_seconds BIGINT DEFAULT NULL,
  threshold_seconds BIGINT NOT NULL,
  sla_status VARCHAR(20) NOT NULL,
  checked_at DATETIME(3) NOT NULL,
  KEY idx_freshness_history (channel,checked_at),
  CONSTRAINT chk_freshness_history_channel CHECK (channel IN ('SDK','JIRA','QUALITY','REDBLACK')),
  CONSTRAINT chk_freshness_history_status CHECK (sla_status IN ('MET','BREACHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

INSERT IGNORE INTO t_system_audit_action_catalog(operation,audit_class,description,sensitive_action) VALUES
('GENERATE_REPORT','BUSINESS','生成AI报告',0),
('REGENERATE_REPORT','BUSINESS','重新生成AI报告并建立修订链',0),
('GENERATE_REPORT_REQUEST','BUSINESS','人工请求生成AI报告',0),
('VIEW_REPORT','QUERY','查看AI报告',0),
('EXPORT_REPORT','SECURITY','导出AI报告PDF',1),
('REPORT_GENERATION_FAILED','SYSTEM','AI报告生成失败',0),
('REPORT_FRESHNESS_SLA_BREACHED','SYSTEM','报告数据新鲜度SLA违约',0),
('REPORT_FRESHNESS_SLA_RECOVERED','SYSTEM','报告数据新鲜度SLA恢复',0);
