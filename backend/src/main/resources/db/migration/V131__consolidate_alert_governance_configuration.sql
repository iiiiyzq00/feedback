-- Consolidate low-cardinality alert governance catalogs into t_system_config.
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,owner_team,effective_to,config_desc,is_active,created_at,updated_at)
SELECT CONCAT('ALERT_CONTRACT:',SHA2(contract_code,256)),'ALERT_DATA_CONTRACT','v1',JSON_OBJECT(
 'contractCode',contract_code,'schemaVersion',schema_version,'sourceSystem',source_system,
 'freshnessSeconds',freshness_seconds,'completenessThreshold',completeness_threshold,
 'evidenceSha256',validation_evidence_sha256,'validationSummary',validation_summary,
 'validatedBy',validated_by,'validatedAt',validated_at,'approvedBy',approved_by,'approvedAt',approved_at),
 'alert-contract-v1',contract_status,IF(contract_status='APPROVED','APPROVED',IF(contract_status='VALIDATED','PENDING','REJECTED')),
 owner_team,expires_at,validation_summary,contract_status NOT IN ('SUSPENDED','EXPIRED'),created_at,updated_at
FROM t_alert_data_contract ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),lifecycle_status=VALUES(lifecycle_status),
 approval_status=VALUES(approval_status),owner_team=VALUES(owner_team),effective_to=VALUES(effective_to),
 config_desc=VALUES(config_desc),is_active=VALUES(is_active),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,owner_team,config_desc,is_active,updated_at)
SELECT CONCAT('ALERT_RULE_CATALOG:',rule_code),'ALERT_RULE_CATALOG','v1',JSON_OBJECT(
 'ruleCode',rule_code,'ruleName',rule_name,'category',category,'defaultSeverity',default_severity,
 'evaluationMode',evaluation_mode,'implementationStatus',implementation_status,
 'implementationEvidenceSha256',implementation_evidence_sha256,'implementationVerifiedBy',implementation_verified_by,
 'implementationVerifiedAt',implementation_verified_at,'parameterTemplate',parameter_template,
 'recoveryPolicy',recovery_policy,'dataDependencies',data_dependencies,'p0NonSilenceable',p0_non_silenceable,
 'sourceReference',source_reference),'alert-rule-cat-v1',governance_status,
 IF(governance_status='APPROVED','APPROVED','PENDING'),owner_team,recovery_policy,
 governance_status<>'DEPRECATED',updated_at
FROM t_alert_rule_catalog ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),lifecycle_status=VALUES(lifecycle_status),
 approval_status=VALUES(approval_status),owner_team=VALUES(owner_team),config_desc=VALUES(config_desc),
 is_active=VALUES(is_active),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,is_active)
SELECT CONCAT('ALERT_RULE_CONTRACT:',SHA2(CONCAT(rule_code,CHAR(31),contract_code),256)),
 'ALERT_RULE_CONTRACT','v1',JSON_OBJECT('ruleCode',rule_code,'contractCode',contract_code,'required',required_flag),
 'alert-rule-link-v1','ACTIVE','APPROVED',1 FROM t_alert_rule_contract_link
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),is_active=1;

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,effective_from,is_active,updated_at)
SELECT CONCAT('ALERT_NOTIFICATION_POLICY:',severity),'ALERT_NOTIFICATION_POLICY',policy_version,JSON_OBJECT(
 'severity',severity,'requiredChannels',required_channels,'allowedChannels',allowed_channels,
 'deliveryMode',delivery_mode,'scheduleTime',schedule_time,'timezone',timezone),
 'alert-notify-v1','ACTIVE','APPROVED',updated_at,1,updated_at FROM t_alert_notification_policy
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),effective_from=VALUES(effective_from),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,effective_to,config_desc,is_active,created_at,updated_at)
SELECT CONCAT('PROVIDER_ACCEPTANCE:',id),'NOTIFICATION_PROVIDER_ACCEPTANCE','v1',JSON_OBJECT(
 'legacyId',id,'channel',notify_channel,'providerCode',provider_code,'environment',environment,
 'capabilities',capability_snapshot,'dailyQuota',daily_quota,'idempotencySupported',idempotency_supported,
 'evidenceSha256',evidence_sha256,'evidenceReference',evidence_reference,'validatedBy',validated_by,
 'validatedAt',validated_at,'approvedBy',approved_by,'approvedAt',approved_at),
 'provider-accept-v1',acceptance_status,IF(acceptance_status='APPROVED','APPROVED',IF(acceptance_status='VALIDATED','PENDING','REJECTED')),
 expires_at,evidence_reference,acceptance_status NOT IN ('SUSPENDED','EXPIRED'),created_at,updated_at
FROM t_notification_provider_acceptance ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),
 lifecycle_status=VALUES(lifecycle_status),approval_status=VALUES(approval_status),effective_to=VALUES(effective_to),
 config_desc=VALUES(config_desc),is_active=VALUES(is_active),updated_at=VALUES(updated_at);

CREATE TRIGGER trg_alert_contract_config_insert AFTER INSERT ON t_alert_data_contract FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,
 approval_status,owner_team,effective_to,config_desc,is_active,created_at,updated_at)
VALUES(CONCAT('ALERT_CONTRACT:',SHA2(NEW.contract_code,256)),'ALERT_DATA_CONTRACT','v1',JSON_OBJECT(
 'contractCode',NEW.contract_code,'schemaVersion',NEW.schema_version,'sourceSystem',NEW.source_system,
 'freshnessSeconds',NEW.freshness_seconds,'completenessThreshold',NEW.completeness_threshold,
 'evidenceSha256',NEW.validation_evidence_sha256,'validationSummary',NEW.validation_summary,
 'validatedBy',NEW.validated_by,'validatedAt',NEW.validated_at,'approvedBy',NEW.approved_by,'approvedAt',NEW.approved_at),
 'alert-contract-v1',NEW.contract_status,IF(NEW.contract_status='APPROVED','APPROVED',IF(NEW.contract_status='VALIDATED','PENDING','REJECTED')),
 NEW.owner_team,NEW.expires_at,NEW.validation_summary,NEW.contract_status NOT IN ('SUSPENDED','EXPIRED'),NEW.created_at,NEW.updated_at);
CREATE TRIGGER trg_alert_contract_config_update AFTER UPDATE ON t_alert_data_contract FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('contractCode',NEW.contract_code,'schemaVersion',NEW.schema_version,
 'sourceSystem',NEW.source_system,'freshnessSeconds',NEW.freshness_seconds,'completenessThreshold',NEW.completeness_threshold,
 'evidenceSha256',NEW.validation_evidence_sha256,'validationSummary',NEW.validation_summary,'validatedBy',NEW.validated_by,
 'validatedAt',NEW.validated_at,'approvedBy',NEW.approved_by,'approvedAt',NEW.approved_at),
 lifecycle_status=NEW.contract_status,approval_status=IF(NEW.contract_status='APPROVED','APPROVED',IF(NEW.contract_status='VALIDATED','PENDING','REJECTED')),
 owner_team=NEW.owner_team,effective_to=NEW.expires_at,config_desc=NEW.validation_summary,
 is_active=NEW.contract_status NOT IN ('SUSPENDED','EXPIRED'),updated_at=NEW.updated_at
WHERE config_key=CONCAT('ALERT_CONTRACT:',SHA2(OLD.contract_code,256)) AND config_version='v1';
CREATE TRIGGER trg_alert_contract_config_delete AFTER DELETE ON t_alert_data_contract FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('ALERT_CONTRACT:',SHA2(OLD.contract_code,256)) AND config_version='v1';

CREATE TRIGGER trg_alert_catalog_config_insert AFTER INSERT ON t_alert_rule_catalog FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,
 approval_status,owner_team,config_desc,is_active,updated_at)
VALUES(CONCAT('ALERT_RULE_CATALOG:',NEW.rule_code),'ALERT_RULE_CATALOG','v1',JSON_OBJECT('ruleCode',NEW.rule_code,
 'ruleName',NEW.rule_name,'category',NEW.category,'defaultSeverity',NEW.default_severity,'evaluationMode',NEW.evaluation_mode,
 'implementationStatus',NEW.implementation_status,'implementationEvidenceSha256',NEW.implementation_evidence_sha256,
 'implementationVerifiedBy',NEW.implementation_verified_by,'implementationVerifiedAt',NEW.implementation_verified_at,
 'parameterTemplate',NEW.parameter_template,'recoveryPolicy',NEW.recovery_policy,'dataDependencies',NEW.data_dependencies,
 'p0NonSilenceable',NEW.p0_non_silenceable,'sourceReference',NEW.source_reference),'alert-rule-cat-v1',
 NEW.governance_status,IF(NEW.governance_status='APPROVED','APPROVED','PENDING'),NEW.owner_team,NEW.recovery_policy,
 NEW.governance_status<>'DEPRECATED',NEW.updated_at);
CREATE TRIGGER trg_alert_catalog_config_update AFTER UPDATE ON t_alert_rule_catalog FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_SET(config_value,'$.ruleName',NEW.rule_name,'$.category',NEW.category,
 '$.defaultSeverity',NEW.default_severity,'$.evaluationMode',NEW.evaluation_mode,'$.implementationStatus',NEW.implementation_status,
 '$.implementationEvidenceSha256',NEW.implementation_evidence_sha256,'$.implementationVerifiedBy',NEW.implementation_verified_by,
 '$.implementationVerifiedAt',NEW.implementation_verified_at,'$.parameterTemplate',NEW.parameter_template,
 '$.recoveryPolicy',NEW.recovery_policy,'$.dataDependencies',NEW.data_dependencies,'$.p0NonSilenceable',NEW.p0_non_silenceable,
 '$.sourceReference',NEW.source_reference),lifecycle_status=NEW.governance_status,
 approval_status=IF(NEW.governance_status='APPROVED','APPROVED','PENDING'),owner_team=NEW.owner_team,
 config_desc=NEW.recovery_policy,is_active=NEW.governance_status<>'DEPRECATED',updated_at=NEW.updated_at
WHERE config_key=CONCAT('ALERT_RULE_CATALOG:',OLD.rule_code) AND config_version='v1';
CREATE TRIGGER trg_alert_catalog_config_delete AFTER DELETE ON t_alert_rule_catalog FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('ALERT_RULE_CATALOG:',OLD.rule_code) AND config_version='v1';

CREATE TRIGGER trg_alert_rule_contract_config_insert AFTER INSERT ON t_alert_rule_contract_link FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,approval_status,is_active)
VALUES(CONCAT('ALERT_RULE_CONTRACT:',SHA2(CONCAT(NEW.rule_code,CHAR(31),NEW.contract_code),256)),
 'ALERT_RULE_CONTRACT','v1',JSON_OBJECT('ruleCode',NEW.rule_code,'contractCode',NEW.contract_code,'required',NEW.required_flag),
 'alert-rule-link-v1','ACTIVE','APPROVED',1);
CREATE TRIGGER trg_alert_rule_contract_config_delete AFTER DELETE ON t_alert_rule_contract_link FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('ALERT_RULE_CONTRACT:',SHA2(CONCAT(OLD.rule_code,CHAR(31),OLD.contract_code),256));

CREATE TRIGGER trg_provider_accept_config_insert AFTER INSERT ON t_notification_provider_acceptance FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,
 approval_status,effective_to,config_desc,is_active,created_at,updated_at)
VALUES(CONCAT('PROVIDER_ACCEPTANCE:',NEW.id),'NOTIFICATION_PROVIDER_ACCEPTANCE','v1',JSON_OBJECT('legacyId',NEW.id,
 'channel',NEW.notify_channel,'providerCode',NEW.provider_code,'environment',NEW.environment,'capabilities',NEW.capability_snapshot,
 'dailyQuota',NEW.daily_quota,'idempotencySupported',NEW.idempotency_supported,'evidenceSha256',NEW.evidence_sha256,
 'evidenceReference',NEW.evidence_reference,'validatedBy',NEW.validated_by,'validatedAt',NEW.validated_at,
 'approvedBy',NEW.approved_by,'approvedAt',NEW.approved_at),'provider-accept-v1',NEW.acceptance_status,
 IF(NEW.acceptance_status='APPROVED','APPROVED',IF(NEW.acceptance_status='VALIDATED','PENDING','REJECTED')),
 NEW.expires_at,NEW.evidence_reference,NEW.acceptance_status NOT IN ('SUSPENDED','EXPIRED'),NEW.created_at,NEW.updated_at);
CREATE TRIGGER trg_provider_accept_config_update AFTER UPDATE ON t_notification_provider_acceptance FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('legacyId',NEW.id,'channel',NEW.notify_channel,
 'providerCode',NEW.provider_code,'environment',NEW.environment,'capabilities',NEW.capability_snapshot,
 'dailyQuota',NEW.daily_quota,'idempotencySupported',NEW.idempotency_supported,'evidenceSha256',NEW.evidence_sha256,
 'evidenceReference',NEW.evidence_reference,'validatedBy',NEW.validated_by,'validatedAt',NEW.validated_at,
 'approvedBy',NEW.approved_by,'approvedAt',NEW.approved_at),lifecycle_status=NEW.acceptance_status,
 approval_status=IF(NEW.acceptance_status='APPROVED','APPROVED',IF(NEW.acceptance_status='VALIDATED','PENDING','REJECTED')),
 effective_to=NEW.expires_at,config_desc=NEW.evidence_reference,is_active=NEW.acceptance_status NOT IN ('SUSPENDED','EXPIRED'),
 updated_at=NEW.updated_at WHERE config_key=CONCAT('PROVIDER_ACCEPTANCE:',OLD.id) AND config_version='v1';
CREATE TRIGGER trg_provider_accept_config_delete AFTER DELETE ON t_notification_provider_acceptance FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('PROVIDER_ACCEPTANCE:',OLD.id) AND config_version='v1';

CREATE TRIGGER trg_notification_policy_config_insert AFTER INSERT ON t_alert_notification_policy FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,lifecycle_status,approval_status,effective_from,is_active,updated_at)
VALUES(CONCAT('ALERT_NOTIFICATION_POLICY:',NEW.severity),'ALERT_NOTIFICATION_POLICY',NEW.policy_version,
 JSON_OBJECT('severity',NEW.severity,'requiredChannels',NEW.required_channels,'allowedChannels',NEW.allowed_channels,
 'deliveryMode',NEW.delivery_mode,'scheduleTime',NEW.schedule_time,'timezone',NEW.timezone),
 'alert-notify-v1','ACTIVE','APPROVED',NEW.updated_at,1,NEW.updated_at);
CREATE TRIGGER trg_notification_policy_config_update AFTER UPDATE ON t_alert_notification_policy FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('severity',NEW.severity,'requiredChannels',NEW.required_channels,
 'allowedChannels',NEW.allowed_channels,'deliveryMode',NEW.delivery_mode,'scheduleTime',NEW.schedule_time,'timezone',NEW.timezone),
 effective_from=NEW.updated_at,updated_at=NEW.updated_at
WHERE config_key=CONCAT('ALERT_NOTIFICATION_POLICY:',OLD.severity) AND config_version=OLD.policy_version;
CREATE TRIGGER trg_notification_policy_config_delete AFTER DELETE ON t_alert_notification_policy FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('ALERT_NOTIFICATION_POLICY:',OLD.severity) AND config_version=OLD.policy_version;
