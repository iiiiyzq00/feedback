SET @d10p_prefix='D10P-20260730A';
SET @d10p_started=NOW(3);

INSERT INTO t_cluster(public_id,cluster_name,product_scope,function_module,issue_category,canonical_symptom,
 total_record_count,channel_distribution,cluster_status,search_index_status,vector_index_status)
WITH RECURSIVE a AS (SELECT 0 n UNION ALL SELECT n+1 FROM a WHERE n<999),
 b AS (SELECT 0 n UNION ALL SELECT n+1 FROM b WHERE n<9)
SELECT UUID_TO_BIN(UUID()),CONCAT(@d10p_prefix,'-CL-',LPAD(a.n*10+b.n,5,'0')),'PERFORMANCE',
 ELT(MOD(a.n*10+b.n,5)+1,'AI_TUTOR','SYNC_COURSE','WRONG_QUESTION_BOOK','CAMERA_MIC','VIDEO_PLAYBACK'),
 ELT(MOD(a.n*10+b.n,4)+1,'BUG','HARDWARE','DEMAND','COMPLAINT'),'D10 isolated performance symptom',10,
 JSON_OBJECT('SDK',3,'JIRA',3,'QUALITY',2,'REDBLACK',2),'ACTIVE','SYNCED','SYNCED'
FROM a CROSS JOIN b;

SET @d10p_cluster_first=(SELECT MIN(id) FROM t_cluster WHERE cluster_name LIKE CONCAT(@d10p_prefix,'-CL-%'));

INSERT INTO t_feedback_source(public_id,source_channel,source_record_id,app_version,package_name,source_event_time,
 ingest_time,province_code,city_code)
WITH RECURSIVE a AS (SELECT 0 n UNION ALL SELECT n+1 FROM a WHERE n<999),
 b AS (SELECT 0 n UNION ALL SELECT n+1 FROM b WHERE n<99)
SELECT UUID_TO_BIN(UUID()),ELT(MOD(a.n*100+b.n,4)+1,'SDK','JIRA','QUALITY','REDBLACK'),
 CONCAT(@d10p_prefix,'-SRC-',LPAD(a.n*100+b.n,6,'0')),CONCAT('10.',MOD(a.n,10),'.',MOD(b.n,20)),
 ELT(MOD(a.n*100+b.n,3)+1,'com.company.tutor','com.company.math','com.company.english'),
 DATE_SUB(@d10p_started,INTERVAL MOD(a.n*100+b.n,2592000) SECOND),@d10p_started,
 ELT(MOD(a.n*100+b.n,5)+1,'SHANGHAI','BEIJING','GUANGDONG','ZHEJIANG','SICHUAN'),
 ELT(MOD(a.n*100+b.n,5)+1,'SHANGHAI','BEIJING','SHENZHEN','HANGZHOU','CHENGDU')
FROM a CROSS JOIN b;

INSERT INTO t_feedback_content(source_id,title,description,function_module,problem_type,severity_level,issue_category,
 user_sentiment,reproducibility,processing_status,masking_status,data_quality_score,quality_rule_version,is_deleted)
SELECT id,CONCAT('D10 performance feedback ',RIGHT(source_record_id,6)),
 CONCAT('D10 isolated sanitized capacity feedback ',source_channel,' ',app_version),
 ELT(MOD(id,5)+1,'AI_TUTOR','SYNC_COURSE','WRONG_QUESTION_BOOK','CAMERA_MIC','VIDEO_PLAYBACK'),
 ELT(MOD(id,4)+1,'CRASH','FREEZE','WRONG_RESULT','LAG'),ELT(MOD(id,5)+1,'S0','S1','S2','S3','S4'),
 ELT(MOD(id,4)+1,'BUG','HARDWARE','DEMAND','COMPLAINT'),ELT(MOD(id,3)+1,'NEGATIVE','NEUTRAL','POSITIVE'),
 ELT(MOD(id,4)+1,'ALWAYS','FREQUENT','OCCASIONAL','ONCE'),'OPEN','PASS',0.950000,'d10p-v1',0
FROM t_feedback_source WHERE source_record_id LIKE CONCAT(@d10p_prefix,'-SRC-%');

INSERT INTO t_feedback_ai(source_id,ai_summary,ai_category,ai_severity,ai_confidence,ai_explanation,recommended_team,
 ai_processed_at,ai_model_version,review_status,text_fingerprint,search_document_id,search_index_status,
 milvus_vector_id,vector_index_status,embedding_model_version,embedding_dimension,embedding_generated_at)
SELECT s.id,'D10 deterministic performance summary',c.issue_category,c.severity_level,0.950000,
 'D10 deterministic fixture','PERFORMANCE_TEAM',@d10p_started,'fixture-d10p-v1','NOT_REQUIRED',s.id,
 CAST(s.id AS CHAR),'SYNCED',CAST(s.id AS CHAR),'SYNCED','fixture-d10p-v1',1024,@d10p_started
FROM t_feedback_source s JOIN t_feedback_content c ON c.source_id=s.id
WHERE s.source_record_id LIKE CONCAT(@d10p_prefix,'-SRC-%');

INSERT INTO t_cluster_link(cluster_id,feedback_source_id,relation_role,link_type,match_similarity,
 decision_version,review_status,is_active)
SELECT @d10p_cluster_first+MOD(CAST(RIGHT(s.source_record_id,6) AS UNSIGNED),10000),s.id,'MAIN','AUTO_HIGH',0.950000,
 'd10p-v1','NOT_REQUIRED',1 FROM t_feedback_source s WHERE s.source_record_id LIKE CONCAT(@d10p_prefix,'-SRC-%');

SELECT TIMESTAMPDIFF(MICROSECOND,@d10p_started,NOW(3))/1000000 generation_seconds,
 (SELECT COUNT(*) FROM t_feedback_source WHERE source_record_id LIKE CONCAT(@d10p_prefix,'-SRC-%')) sources,
 (SELECT COUNT(*) FROM t_cluster WHERE cluster_name LIKE CONCAT(@d10p_prefix,'-CL-%')) clusters;
