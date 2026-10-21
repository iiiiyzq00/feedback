UPDATE t_metric_definition
SET display_name='SDK 接入健康',
    definition_text='feedback入口请求成功率、429、鉴权/参数失败、5xx、P95与接收后处理状态；入口请求不保存正文、签名和原始IP',
    metric_version='sdk-transport-v1',
    drilldown_path='/monitor/operations/sdk-requests'
WHERE metric_code='SDK_INGEST_RPS';

UPDATE t_alert_data_contract
SET validation_evidence_sha256=SHA2('sdk-request-telemetry-v1:filter+schema+acceptance-2xx-401-429',256),
    validation_summary='V74入口过滤器与表结构验收通过：2xx、401和429均按应用、错误码与耗时落库；不保存正文、签名与原始IP'
WHERE contract_code='sdk_request_telemetry';

UPDATE t_alert_rule_catalog
SET implementation_evidence_sha256=SHA2(CONCAT(rule_code,':sdk-transport-v1:20-request-replay'),256)
WHERE rule_code IN('SDK_REQUEST_FAILURE_RATE_HIGH','SDK_RATE_LIMIT_RATE_HIGH');
