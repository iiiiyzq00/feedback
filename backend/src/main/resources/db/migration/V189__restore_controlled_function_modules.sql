-- V187 preserved Jira's bounded raw module label in t_channel_jira.module_raw, but
-- also copied it into the formal cross-channel function_module dimension. Restore
-- the controlled semantic boundary without deleting the source fact.
UPDATE t_feedback_content
SET function_module=CASE
  WHEN function_module IN ('网络相关','网络稳定性','用户网络环境','网络卡顿','WiFi模组') THEN 'NETWORK_CONNECTION'
  WHEN function_module IN ('麦克风','硬件麦克风') THEN 'CAMERA_MIC'
  WHEN function_module IN ('管控相关','系统_管控相关') THEN 'PARENT_CONTROL'
  ELSE function_module
END
WHERE function_module IN (
  '网络相关','网络稳定性','用户网络环境','网络卡顿','WiFi模组',
  '麦克风','硬件麦克风','管控相关','系统_管控相关'
);

UPDATE t_feedback_content
SET function_module=NULL
WHERE NULLIF(TRIM(function_module),'') IS NOT NULL
  AND function_module NOT IN (
    'AI_TUTOR','AI_HOMEWORK_CHECK','AI_ESSAY_CHECK','SYNC_COURSE','EXERCISE_PRACTICE',
    'WRONG_QUESTION_BOOK','VIDEO_PLAYBACK','TEXTBOOK_READING','PARENT_CONTROL','DEVICE_SETTINGS',
    'NETWORK_CONNECTION','CAMERA_MIC','SCREEN_DISPLAY','APP_STORE','VIDEO_CALL','SCREEN_OFF_STUDY',
    'POSTURE_MONITOR','HEALTH_OVERVIEW','DRAWING_CANVAS','OTHER'
  );

UPDATE t_cluster cl
LEFT JOIN (
  SELECT l.cluster_id,MAX(c.function_module) function_module
  FROM t_cluster_link l
  JOIN t_feedback_content c ON c.source_id=l.feedback_source_id
  WHERE l.is_active=1 AND l.relation_role='MAIN' AND c.is_deleted=0
    AND c.masking_status='PASS' AND NULLIF(TRIM(c.function_module),'') IS NOT NULL
  GROUP BY l.cluster_id
  HAVING COUNT(DISTINCT c.function_module)=1
) agreed ON agreed.cluster_id=cl.id
SET cl.function_module=agreed.function_module,
    cl.search_index_status='PENDING',cl.vector_index_status='PENDING',cl.updated_at=CURRENT_TIMESTAMP(3)
WHERE cl.function_module IS NOT NULL
  AND cl.function_module NOT IN (
    'AI_TUTOR','AI_HOMEWORK_CHECK','AI_ESSAY_CHECK','SYNC_COURSE','EXERCISE_PRACTICE',
    'WRONG_QUESTION_BOOK','VIDEO_PLAYBACK','TEXTBOOK_READING','PARENT_CONTROL','DEVICE_SETTINGS',
    'NETWORK_CONNECTION','CAMERA_MIC','SCREEN_DISPLAY','APP_STORE','VIDEO_CALL','SCREEN_OFF_STUDY',
    'POSTURE_MONITOR','HEALTH_OVERVIEW','DRAWING_CANVAS','OTHER'
  );
