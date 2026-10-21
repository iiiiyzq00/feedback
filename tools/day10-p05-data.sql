SET @p05_prefix='D10P05-20260730A';
SET @p05_started=NOW(3);

INSERT INTO t_feedback_source(public_id,source_channel,source_record_id,app_version,package_name,
 source_event_time,ingest_time,province_code,city_code)
WITH RECURSIVE n AS (SELECT 0 i UNION ALL SELECT i+1 FROM n WHERE i<999)
SELECT UUID_TO_BIN(UUID()),ELT(MOD(i,4)+1,'SDK','JIRA','QUALITY','REDBLACK'),
 CONCAT(@p05_prefix,'-',LPAD(i,4,'0')),CONCAT('P05.',MOD(i,10),'.',MOD(i,20)),
 'com.company.p05',DATE_SUB(@p05_started,INTERVAL i SECOND),@p05_started,
 ELT(MOD(i,4)+1,'SHANGHAI','BEIJING','GUANGDONG','ZHEJIANG'),
 ELT(MOD(i,4)+1,'SHANGHAI','BEIJING','SHENZHEN','HANGZHOU') FROM n;

INSERT INTO t_feedback_content(source_id,title,description,function_module,problem_type,severity_level,
 issue_category,user_sentiment,reproducibility,processing_status,masking_status,data_quality_score,
 quality_rule_version,is_deleted)
SELECT id,CONCAT('D10P05_SYNC_COURSE ',RIGHT(source_record_id,4)),
 CONCAT(CASE MOD(id,4) WHEN 0 THEN 'D10P05_VECTOR_CAMERA 摄像头开启后课程崩溃'
   WHEN 1 THEN 'D10P05_VECTOR_AUDIO 麦克风开启后课程卡顿'
   WHEN 2 THEN 'D10P05_VECTOR_SYNC 同步课程进度失败'
   ELSE 'D10P05_VECTOR_VIDEO 视频播放出现黑屏' END,' isolated sanitized performance evidence ',source_record_id),
 ELT(MOD(id,4)+1,'CAMERA_MIC','SYNC_COURSE','VIDEO_PLAYBACK','AI_TUTOR'),
 ELT(MOD(id,3)+1,'CRASH','LAG','WRONG_RESULT'),ELT(MOD(id,5)+1,'S0','S1','S2','S3','S4'),
 'BUG','NEGATIVE','FREQUENT','OPEN','PASS',0.990000,'d10-p05-v1',0
FROM t_feedback_source WHERE source_record_id LIKE CONCAT(@p05_prefix,'%');

INSERT INTO t_feedback_ai(source_id,ai_summary,ai_category,ai_severity,ai_confidence,ai_explanation,
 recommended_team,ai_processed_at,ai_model_version,review_status,text_fingerprint,
 search_index_status,vector_index_status)
SELECT s.id,'P05 isolated real index convergence','BUG',c.severity_level,0.990000,
 'P05 deterministic fixture awaiting real derived indexes','PERFORMANCE_TEAM',@p05_started,
 'fixture-p05-input-v1','NOT_REQUIRED',s.id,'PENDING','PENDING'
FROM t_feedback_source s JOIN t_feedback_content c ON c.source_id=s.id
WHERE s.source_record_id LIKE CONCAT(@p05_prefix,'%');

SELECT TIMESTAMPDIFF(MICROSECOND,@p05_started,NOW(3))/1000000 generation_seconds,
 COUNT(*) sources FROM t_feedback_source WHERE source_record_id LIKE CONCAT(@p05_prefix,'%');
