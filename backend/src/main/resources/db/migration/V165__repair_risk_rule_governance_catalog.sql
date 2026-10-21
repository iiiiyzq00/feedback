-- V163 published six additional governed risk rules but left the closed
-- governance baseline at 36. It also created compact catalog entries that
-- omitted the metadata consumed by the governance page. Repair both pieces
-- from the active risk-v1 runtime definitions.

UPDATE t_system_config c
JOIN t_alert_rule r
  ON r.rule_code = JSON_UNQUOTE(JSON_EXTRACT(c.config_value,'$.ruleCode'))
 AND r.version = 'risk-v1'
 AND r.version_status = 'ACTIVE'
SET c.config_value = JSON_MERGE_PATCH(
      c.config_value,
      JSON_OBJECT(
        'ruleCode', r.rule_code,
        'ruleName', r.rule_name,
        'category', r.category,
        'defaultSeverity', r.default_severity,
        'ownerTeam', r.owner_team,
        'evaluationMode', IF(
          JSON_UNQUOTE(JSON_EXTRACT(r.trigger_expression,'$.mode')) = 'EVENT',
          'EVENT',
          'SCHEDULED'
        ),
        'implementationStatus', 'IMPLEMENTED',
        'implementationEvidenceSha256', SHA2(CONCAT('risk-warning-v1:',r.rule_code),256),
        'parameterTemplate', r.trigger_expression,
        'recoveryPolicy', r.recovery_expression,
        'dataDependencies', COALESCE(JSON_EXTRACT(r.trigger_expression,'$.conditions'),JSON_ARRAY()),
        'p0NonSilenceable', IF(
          JSON_UNQUOTE(JSON_EXTRACT(r.routing_config,'$.ordinarySilenceAllowed')) = 'false',
          TRUE,
          FALSE
        ),
        'sourceReference', JSON_UNQUOTE(JSON_EXTRACT(r.routing_config,'$.source')),
        'governanceStatus', 'APPROVED',
        'builtIn', TRUE
      )
    ),
    c.config_schema_version = 'alert-rule-catalog-v1',
    c.lifecycle_status = 'APPROVED',
    c.approval_status = 'APPROVED',
    c.owner_team = r.owner_team,
    c.is_active = 1,
    c.updated_at = NOW(3)
WHERE c.config_type = 'ALERT_RULE_CATALOG';

-- The governed catalog now contains the original 36 entries plus the six
-- genuinely new risk-warning rules introduced by V163.
UPDATE t_system_config
SET config_value = CAST('42' AS JSON),
    config_desc = '正式治理规则数（含SDK入口遥测与risk-v1风险预警规则）',
    updated_at = NOW(3)
WHERE config_key = 'alert.governance.expected-rule-count'
  AND is_active = 1;
