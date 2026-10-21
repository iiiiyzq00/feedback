UPDATE t_metric_definition
SET display_name='AI结果已生成',
    definition_text='隐私通过且 ai_processed_at 非空；表示已产出分类结果，不表示无需人工复核',
    metric_version='record-funnel-v6'
WHERE metric_code='AI_PROCESSED';

UPDATE t_metric_definition
SET display_name='AI结果待人工确认',
    definition_text='当前全量积压：PASS、未删除、ai_processed_at 非空且 review_status=PENDING，按 source_id 去重',
    metric_version='ai-review-backlog-v3'
WHERE metric_code='AI_REVIEW_BACKLOG';
