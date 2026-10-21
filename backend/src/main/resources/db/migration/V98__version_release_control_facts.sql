CREATE TABLE t_app_version_release_fact (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  package_name VARCHAR(150) NOT NULL,
  app_version VARCHAR(50) NOT NULL,
  control_version VARCHAR(50) NOT NULL,
  released_at DATETIME(3) NOT NULL,
  release_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_app_version_release(package_name,app_version),
  KEY idx_app_version_release_time(released_at),
  CONSTRAINT chk_app_version_release_status CHECK(release_status IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT chk_app_version_release_control CHECK(app_version<>control_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='版本回归告警的发布版本与对照版本治理事实';

UPDATE t_alert_data_contract SET schema_version='version-release-control-v1',source_system='t_app_version_release_fact+t_feedback_source',freshness_seconds=300,completeness_threshold=.990000
WHERE contract_code IN ('app_version','control_version') AND contract_status='DRAFT';

INSERT INTO t_alert_rule(rule_code,rule_name,category,owner_team,priority,version,version_status,default_severity,trigger_expression,recovery_expression,routing_config,cooldown_minutes,is_enabled)
SELECT 'VERSION_REGRESSION','版本回归','PRODUCT_TREND','产品+研发',320,'shadow-v1','SHADOW','P1',JSON_OBJECT('combinator','ALL','conditions',JSON_ARRAY(JSON_OBJECT('metric','VERSION_REGRESSION_CANDIDATE_COUNT','operator','>=','threshold',1,'window','30m'))),JSON_OBJECT('threshold',0),JSON_OBJECT('channels',JSON_ARRAY('IN_APP'),'deliveryMode','TEST_ISOLATED'),60,0 WHERE NOT EXISTS(SELECT 1 FROM t_alert_rule WHERE rule_code='VERSION_REGRESSION' AND version_status='SHADOW');
