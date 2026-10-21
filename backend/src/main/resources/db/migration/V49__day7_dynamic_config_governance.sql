-- D7-S01..S08: typed catalog, atomic change sets, history and rollback lineage.
ALTER TABLE t_system_config
  ADD COLUMN change_set_id CHAR(36) DEFAULT NULL AFTER config_desc,
  ADD COLUMN change_reason VARCHAR(500) DEFAULT NULL AFTER change_set_id,
  ADD COLUMN source_config_id BIGINT DEFAULT NULL AFTER change_reason,
  ADD KEY idx_config_change_set (change_set_id),
  ADD KEY idx_config_source (source_config_id);

CREATE TABLE t_system_config_catalog (
  config_key VARCHAR(100) PRIMARY KEY,
  value_type VARCHAR(20) NOT NULL,
  min_value DECIMAL(20,6) DEFAULT NULL,
  max_value DECIMAL(20,6) DEFAULT NULL,
  hot_reload BOOLEAN NOT NULL DEFAULT TRUE,
  secret_value BOOLEAN NOT NULL DEFAULT FALSE,
  owner_module VARCHAR(50) NOT NULL,
  description VARCHAR(500) NOT NULL,
  CONSTRAINT chk_config_catalog_type CHECK (value_type IN ('INTEGER','DECIMAL','BOOLEAN','STRING','JSON')),
  CONSTRAINT chk_config_catalog_range CHECK (min_value IS NULL OR max_value IS NULL OR min_value<=max_value),
  CONSTRAINT chk_config_secret_dynamic CHECK (secret_value=FALSE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='允许动态修改的非密钥配置目录';

INSERT INTO t_system_config_catalog(config_key,value_type,min_value,max_value,owner_module,description) VALUES
('sdk.sign-window-seconds','INTEGER',60,600,'SDK','签名允许时间窗口'),
('sdk.rate-limit','INTEGER',1,1000,'SDK','每个应用请求速率'),
('sdk.nonce-expire-minutes','INTEGER',1,1440,'SDK','Nonce去重保留时间'),
('attachment.max-mb','INTEGER',1,100,'INGEST','单附件大小上限'),
('cipher.retention-days','INTEGER',1,90,'SECURITY','待处理密文保留天数'),
('cluster.top-k','INTEGER',1,50,'CLUSTER','归簇候选召回数量'),
('ai.timeout-seconds','INTEGER',5,120,'AI','外部模型调用超时'),
('import.rollback-hours','INTEGER',1,72,'IMPORT','导入批次回滚窗口');

UPDATE t_system_config SET change_set_id=UUID(),change_reason='V49历史配置迁移' WHERE change_set_id IS NULL;
