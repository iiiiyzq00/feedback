-- The SDK dashboard now distinguishes transport attempts, accepted ingests and
-- post-ingest processing. Keep the two transport alerts mutually exclusive:
-- non-rate-limit entry failures are operational failures, while HTTP 429 is a
-- separate capacity/client-traffic signal.
UPDATE t_alert_rule_catalog
SET rule_name = 'SDK入口非限流失败率异常',
    parameter_template = JSON_OBJECT(
      'window', '5m',
      'minRequests', 20,
      'failureRatePercent', 5,
      'resultTypes', JSON_ARRAY('AUTH_FAILED', 'BAD_REQUEST', 'SERVER_ERROR', 'OTHER_ERROR')
    ),
    recovery_policy = '5分钟窗口内非限流失败率低于1%后自动恢复',
    source_reference = 'SDK入口请求遥测（排除SUCCESS与RATE_LIMITED）',
    implementation_evidence_sha256 = SHA2('sdk-transport-alert-v2:non-rate-limit-failure', 256),
    implementation_verified_at = NOW(3)
WHERE rule_code = 'SDK_REQUEST_FAILURE_RATE_HIGH';

UPDATE t_alert_rule_catalog
SET rule_name = 'SDK入口请求限流率异常',
    parameter_template = JSON_OBJECT(
      'window', '5m',
      'minRequests', 20,
      'rateLimitPercent', 5,
      'resultTypes', JSON_ARRAY('RATE_LIMITED')
    ),
    recovery_policy = '5分钟窗口内限流拒绝率低于1%后自动恢复',
    source_reference = 'SDK入口请求遥测（RATE_LIMITED/HTTP 429）',
    implementation_evidence_sha256 = SHA2('sdk-transport-alert-v2:rate-limited', 256),
    implementation_verified_at = NOW(3)
WHERE rule_code = 'SDK_RATE_LIMIT_RATE_HIGH';

UPDATE t_alert_rule
SET rule_name = 'SDK入口非限流失败率异常',
    version = 'telemetry-v2',
    trigger_expression = JSON_OBJECT('combinator', 'ALL', 'conditions', JSON_ARRAY(
      JSON_OBJECT('metric', 'SDK_REQUEST_COUNT', 'operator', '>=', 'threshold', 20, 'window', '5m'),
      JSON_OBJECT('metric', 'SDK_NON_RATE_LIMIT_FAILURE_RATE', 'operator', '>=', 'threshold', 5, 'window', '5m')
    )),
    recovery_expression = JSON_OBJECT('threshold', 1),
    updated_at = NOW(3)
WHERE rule_code = 'SDK_REQUEST_FAILURE_RATE_HIGH'
  AND version_status = 'ACTIVE';

UPDATE t_alert_rule
SET rule_name = 'SDK入口请求限流率异常',
    version = 'telemetry-v2',
    trigger_expression = JSON_OBJECT('combinator', 'ALL', 'conditions', JSON_ARRAY(
      JSON_OBJECT('metric', 'SDK_REQUEST_COUNT', 'operator', '>=', 'threshold', 20, 'window', '5m'),
      JSON_OBJECT('metric', 'SDK_RATE_LIMIT_RATE', 'operator', '>=', 'threshold', 5, 'window', '5m')
    )),
    recovery_expression = JSON_OBJECT('threshold', 1),
    updated_at = NOW(3)
WHERE rule_code = 'SDK_RATE_LIMIT_RATE_HIGH'
  AND version_status = 'ACTIVE';
