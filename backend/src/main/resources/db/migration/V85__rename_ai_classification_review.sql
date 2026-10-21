UPDATE t_metric_definition
SET display_name='AI分类待复核',
    definition_text='当前全量待复核：PASS、未删除、ai_processed_at 非空且 review_status=PENDING，按 source_id 去重',
    metric_version='ai-review-backlog-v5'
WHERE metric_code='AI_REVIEW_BACKLOG';
