UPDATE t_metric_definition
SET display_name='SDK 接入健康',
    definition_text='按所选窗口统计后端已接收回执、处理完成、处理中、待人工检查及重试/失败；不含入口拒绝请求',
    metric_version='sdk-health-v1'
WHERE metric_code='SDK_INGEST_RPS';

UPDATE t_system_config_catalog
SET description='SDK 聚合接入速率观察比例；已退出接入概览，仅保留历史配置兼容'
WHERE config_key='sdk.rate-warning-utilization';
