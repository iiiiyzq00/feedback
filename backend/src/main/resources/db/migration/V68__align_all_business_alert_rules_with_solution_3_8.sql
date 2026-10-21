-- Solution 3.8.4 is the authoritative, closed catalog of 34 business rules.
DELETE l FROM t_alert_rule_contract_link l
JOIN t_alert_rule_catalog c ON c.rule_code = l.rule_code
WHERE c.category = 'CUSTOM';

DELETE FROM t_alert_rule_catalog WHERE category = 'CUSTOM';

-- Preserve historical rows, but retire every non-catalog demo/custom version.
-- SYSTEM notification self-monitoring remains outside the business catalog.
UPDATE t_alert_rule r
LEFT JOIN t_alert_rule_catalog c ON c.rule_code = r.rule_code
SET r.is_enabled = 0, r.version_status = 'RETIRED'
WHERE r.version_status = 'ACTIVE'
  AND r.category <> 'SYSTEM'
  AND c.rule_code IS NULL;

-- Unified trend semantics from 3.8.3. These governance parameters do not
-- bypass activation contracts or implementation evidence.
UPDATE t_alert_rule_catalog
SET parameter_template = JSON_SET(parameter_template,
    '$.baselineMethod', 'MEDIAN_MAD', '$.baselineDays', 28,
    '$.percentiles', JSON_ARRAY('P90','P95'), '$.recoveryWindows', 2,
    '$.recoveryRatio', 0.70, '$.dataCompletenessRequired', true)
WHERE rule_code IN ('CLUSTER_GROWTH_ANOMALY','NEGATIVE_SENTIMENT_SURGE');

UPDATE t_alert_rule_catalog
SET parameter_template = JSON_SET(parameter_template,
    '$.baselineMethod', 'MEDIAN_MAD', '$.baselineDays', 28,
    '$.upperPercentile', 'P95', '$.lowerPercentile', 'P10',
    '$.recoveryWindows', 2, '$.dataCompletenessRequired', true)
WHERE rule_code = 'SDK_VOLUME_ANOMALY';

UPDATE t_alert_rule_catalog
SET parameter_template = JSON_SET(parameter_template,
    '$.recoveryWindows', 2, '$.recoveryRate', 0.005)
WHERE rule_code IN ('ATTACHMENT_FAILURE_RATE_HIGH','MASKING_FAILURE_RATE_HIGH');

UPDATE t_alert_rule_catalog
SET parameter_template = JSON_SET(parameter_template,
    '$.recoveryWindows', 2, '$.recoveryPassRate', 0.90)
WHERE rule_code = 'DATA_QUALITY_DROP';

UPDATE t_alert_rule_catalog
SET parameter_template = JSON_SET(parameter_template,
    '$.recoveryWindows', 2, '$.recoveryCompleteness', 0.99)
WHERE rule_code = 'RULE_INPUT_DATA_INCOMPLETE';

UPDATE t_system_config SET config_value = '34'
WHERE config_key = 'alert.governance.expected-rule-count' AND is_active = 1;

-- No formal runtime version may remain enabled when the production evidence
-- or any mandatory data contract is missing/expired.
UPDATE t_alert_rule r
JOIN t_alert_rule_catalog c ON c.rule_code = r.rule_code
SET r.is_enabled = 0
WHERE r.version_status = 'ACTIVE'
  AND (
    c.governance_status <> 'APPROVED'
    OR c.implementation_status <> 'IMPLEMENTED'
    OR c.implementation_evidence_sha256 IS NULL
    OR EXISTS (
      SELECT 1 FROM t_alert_rule_contract_link l
      LEFT JOIN t_alert_data_contract dc ON dc.contract_code = l.contract_code
      WHERE l.rule_code = r.rule_code AND l.required_flag = 1
        AND (dc.contract_status <> 'APPROVED' OR dc.expires_at <= NOW(3))
    )
  );
