-- 学习机可靠双写对账。
-- 必需的会话变量：
--   SET @idempotency_key = 'station:<sha256>';
-- 通过 tools/verify-station-dual-write.sh 执行，或使用 mysql --init-command。

SELECT
  t.id AS task_id,
  t.idempotency_key,
  t.api_version,
  t.old_fbp_status,
  t.new_platform_status,
  t.overall_status,
  t.retry_count,
  t.max_retry_count,
  t.last_error_code,
  t.last_error_summary,
  t.feedback_source_id,
  t.created_at,
  t.completed_at,
  s.source_channel,
  s.source_record_id,
  c.masking_status,
  c.processing_status,
  COUNT(DISTINCT l.id) AS active_cluster_links
FROM t_station_dual_write_task t
LEFT JOIN t_feedback_source s ON s.id = t.feedback_source_id
LEFT JOIN t_feedback_content c ON c.source_id = s.id AND c.is_deleted = 0
LEFT JOIN t_cluster_link l ON l.feedback_source_id = s.id AND l.is_active = 1
WHERE t.idempotency_key = @idempotency_key
GROUP BY
  t.id, t.idempotency_key, t.api_version, t.old_fbp_status,
  t.new_platform_status, t.overall_status, t.retry_count,
  t.max_retry_count, t.last_error_code, t.last_error_summary,
  t.feedback_source_id, t.created_at, t.completed_at,
  s.source_channel, s.source_record_id, c.masking_status,
  c.processing_status;

SELECT
  COUNT(*) AS task_count,
  SUM(t.overall_status = 'COMPLETED') AS completed_task_count,
  SUM(t.old_fbp_status = 'SUCCEEDED') AS old_fbp_success_count,
  SUM(t.new_platform_status = 'SUCCEEDED') AS new_platform_success_count,
  COUNT(DISTINCT s.id) AS source_count,
  SUM(s.source_channel = 'STATION_FEEDBACK') AS station_source_count,
  SUM(s.source_record_id = t.idempotency_key) AS matching_source_key_count,
  SUM(c.masking_status = 'PASS') AS masking_pass_count,
  COUNT(DISTINCT CASE WHEN l.is_active = 1 THEN l.id END) AS active_cluster_link_count,
  CASE
    WHEN COUNT(*) = 1
      AND SUM(t.overall_status = 'COMPLETED') = 1
      AND SUM(t.old_fbp_status = 'SUCCEEDED') = 1
      AND SUM(t.new_platform_status = 'SUCCEEDED') = 1
      AND COUNT(DISTINCT s.id) = 1
      AND SUM(s.source_channel = 'STATION_FEEDBACK') = 1
      AND SUM(s.source_record_id = t.idempotency_key) = 1
      AND SUM(c.masking_status = 'PASS') = 1
    THEN 'PASS'
    ELSE 'FAIL'
  END AS dual_write_acceptance
FROM t_station_dual_write_task t
LEFT JOIN t_feedback_source s ON s.id = t.feedback_source_id
LEFT JOIN t_feedback_content c ON c.source_id = s.id AND c.is_deleted = 0
LEFT JOIN t_cluster_link l ON l.feedback_source_id = s.id
WHERE t.idempotency_key = @idempotency_key;

SELECT
  overall_status,
  old_fbp_status,
  new_platform_status,
  COUNT(*) AS task_count,
  MIN(created_at) AS oldest_created_at,
  MAX(updated_at) AS latest_updated_at,
  MAX(retry_count) AS max_retry_count_seen
FROM t_station_dual_write_task
GROUP BY overall_status, old_fbp_status, new_platform_status
ORDER BY overall_status, old_fbp_status, new_platform_status;
