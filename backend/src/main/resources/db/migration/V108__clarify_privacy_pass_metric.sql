UPDATE t_metric_definition
SET display_name='隐私安全放行',
    definition_text='正式接收中 masking_status=PASS 的安全放行子集；不包含 REVIEW_NEEDED、FAIL 或 PENDING',
    metric_version='record-funnel-v7'
WHERE metric_code='PRIVACY_PASS';

UPDATE t_metric_definition
SET metric_version='record-funnel-v7'
WHERE metric_code IN ('INGEST_RECEIVED','AI_PROCESSED','CLUSTER_DECIDED');
