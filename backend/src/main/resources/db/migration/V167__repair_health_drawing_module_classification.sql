-- Align the controlled module catalog with the two SDK demo applications.
-- Only repair records whose original SDK ingest fact still proves the module;
-- ambiguous UNKNOWN records remain pending instead of being guessed.
UPDATE t_feedback_content c
JOIN t_sdk_ingest i ON i.feedback_source_id = c.source_id
SET c.function_module = i.function_module,
    c.updated_at = CURRENT_TIMESTAMP(3)
WHERE (c.function_module IS NULL OR c.function_module = '' OR c.function_module = 'UNKNOWN')
  AND i.ingest_status = 'COMPLETED'
  AND i.function_module IN ('HEALTH_OVERVIEW', 'DRAWING_CANVAS');

-- A cluster can inherit a module only when all of its current eligible MAIN
-- feedback agrees on one value. Mixed or ambiguous clusters are untouched.
UPDATE t_cluster cl
JOIN (
    SELECT l.cluster_id, MAX(c.function_module) AS function_module
    FROM t_cluster_link l
    JOIN t_feedback_content c ON c.source_id = l.feedback_source_id
    WHERE l.is_active = 1
      AND l.relation_role = 'MAIN'
      AND c.is_deleted = 0
      AND c.masking_status = 'PASS'
      AND c.function_module IN ('HEALTH_OVERVIEW', 'DRAWING_CANVAS')
    GROUP BY l.cluster_id
    HAVING COUNT(DISTINCT c.function_module) = 1
) agreed ON agreed.cluster_id = cl.id
SET cl.function_module = agreed.function_module,
    cl.search_index_status = 'PENDING',
    cl.vector_index_status = 'PENDING',
    cl.updated_at = CURRENT_TIMESTAMP(3)
WHERE cl.function_module IS NULL OR cl.function_module = '' OR cl.function_module = 'UNKNOWN';
