SET @p07='D10P07-20260730A';

INSERT INTO t_import_batch(public_id,batch_id,source_channel,original_filename,content_sha256,normalized_content_hash,
 total_rows,success_rows,batch_status,reconciliation_status,created_at,completed_at,updated_at)
VALUES
(UUID_TO_BIN(UUID()),CONCAT(@p07,'-B001K'),'JIRA','p07-1k.xlsx',UNHEX(SHA2(CONCAT(@p07,'-h1'),256)),UNHEX(SHA2(CONCAT(@p07,'-n1'),256)),1000,1000,'COMPLETED','MATCHED',NOW(3),NOW(3),NOW(3)),
(UUID_TO_BIN(UUID()),CONCAT(@p07,'-B010K'),'QUALITY','p07-10k.xlsx',UNHEX(SHA2(CONCAT(@p07,'-h2'),256)),UNHEX(SHA2(CONCAT(@p07,'-n2'),256)),10000,10000,'COMPLETED','MATCHED',NOW(3),NOW(3),NOW(3)),
(UUID_TO_BIN(UUID()),CONCAT(@p07,'-B050K'),'REDBLACK','p07-50k.xlsx',UNHEX(SHA2(CONCAT(@p07,'-h3'),256)),UNHEX(SHA2(CONCAT(@p07,'-n3'),256)),50000,50000,'COMPLETED','MATCHED',NOW(3),NOW(3),NOW(3));

SET @b1=(SELECT id FROM t_import_batch WHERE batch_id=CONCAT(@p07,'-B001K'));
SET @b2=(SELECT id FROM t_import_batch WHERE batch_id=CONCAT(@p07,'-B010K'));
SET @b3=(SELECT id FROM t_import_batch WHERE batch_id=CONCAT(@p07,'-B050K'));

INSERT INTO t_feedback_source(public_id,source_channel,source_record_id,app_version,source_event_time,ingest_time,
 import_batch_id,province_code,city_code)
WITH RECURSIVE a AS (SELECT 0 n UNION ALL SELECT n+1 FROM a WHERE n<609),
 b AS (SELECT 0 n UNION ALL SELECT n+1 FROM b WHERE n<99)
SELECT UUID_TO_BIN(UUID()),CASE WHEN a.n*100+b.n<1000 THEN 'JIRA' WHEN a.n*100+b.n<11000 THEN 'QUALITY' ELSE 'REDBLACK' END,
 CONCAT(@p07,'-SRC-',LPAD(a.n*100+b.n,5,'0')),'P07.1.0',DATE_SUB(NOW(3),INTERVAL MOD(a.n*100+b.n,3600) SECOND),NOW(3),
 CASE WHEN a.n*100+b.n<1000 THEN @b1 WHEN a.n*100+b.n<11000 THEN @b2 ELSE @b3 END,
 'SHANGHAI','SHANGHAI' FROM a CROSS JOIN b;

INSERT INTO t_feedback_content(source_id,title,description,function_module,problem_type,severity_level,issue_category,
 processing_status,masking_status,data_quality_score,quality_rule_version,is_deleted)
SELECT id,CONCAT('P07 batch item ',RIGHT(source_record_id,5)),'P07 sanitized batch performance evidence',
 'AI_TUTOR','BUG','S2','BUG','OPEN','PASS',0.990000,'d10-p07-v1',0
FROM t_feedback_source WHERE source_record_id LIKE CONCAT(@p07,'%');

INSERT INTO t_feedback_ai(source_id,ai_summary,ai_category,ai_severity,ai_confidence,ai_explanation,ai_processed_at,
 ai_model_version,review_status,search_document_id,search_index_status,milvus_vector_id,vector_index_status)
SELECT id,'P07 batch review candidate','BUG','S2',0.600000,'P07 deterministic review fixture',NOW(3),
 'fixture-p07-v1',CASE WHEN CAST(RIGHT(source_record_id,5) AS UNSIGNED)<600 THEN 'PENDING' ELSE 'NOT_REQUIRED' END,
 CAST(id AS CHAR),'SYNCED',CAST(id AS CHAR),'SYNCED'
FROM t_feedback_source WHERE source_record_id LIKE CONCAT(@p07,'%');

INSERT INTO t_cluster(public_id,cluster_name,product_scope,function_module,issue_category,canonical_symptom,
 total_record_count,channel_distribution,cluster_status,search_index_status,vector_index_status)
VALUES(UUID_TO_BIN(UUID()),CONCAT(@p07,'-CLUSTER'),'P07','AI_TUTOR','BUG','P07 candidate cluster',0,
 JSON_OBJECT('JIRA',600),'ACTIVE','SYNCED','SYNCED');
SET @p07_cluster=(SELECT id FROM t_cluster WHERE cluster_name=CONCAT(@p07,'-CLUSTER'));
INSERT INTO t_cluster_link(cluster_id,feedback_source_id,relation_role,link_type,match_similarity,decision_version,
 review_status,review_priority,review_due_at,review_version,candidate_rank,is_active)
SELECT @p07_cluster,s.id,'CANDIDATE','CANDIDATE',0.700000,'p07-v1','PENDING','P2',DATE_ADD(NOW(3),INTERVAL 2 HOUR),0,1,1
FROM t_feedback_source s WHERE s.source_record_id LIKE CONCAT(@p07,'%')
  AND CAST(RIGHT(s.source_record_id,5) AS UNSIGNED)<600;

SELECT (SELECT COUNT(*) FROM t_feedback_source WHERE source_record_id LIKE CONCAT(@p07,'%')) sources,
 (SELECT COUNT(*) FROM t_feedback_ai a JOIN t_feedback_source s ON s.id=a.source_id WHERE s.source_record_id LIKE CONCAT(@p07,'%') AND a.review_status='PENDING') pending_reviews,
 (SELECT COUNT(*) FROM t_cluster_link l JOIN t_feedback_source s ON s.id=l.feedback_source_id WHERE s.source_record_id LIKE CONCAT(@p07,'%') AND l.review_status='PENDING') cluster_reviews;
