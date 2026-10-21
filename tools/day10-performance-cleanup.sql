SET @d10p_prefix='D10P-20260730A';
CREATE TEMPORARY TABLE d10p_source_ids(id BIGINT PRIMARY KEY);
INSERT INTO d10p_source_ids SELECT id FROM t_feedback_source WHERE source_record_id LIKE 'D10P-%' OR source_record_id LIKE 'D10P03-%';

DELETE h FROM t_system_audit_log h JOIN d10p_source_ids p ON h.operation='FIELD_CONVERSION' AND h.target_type='FEEDBACK' AND h.target_id=CAST(p.id AS CHAR);
DELETE t FROM t_ai_task t JOIN d10p_source_ids p ON p.id=t.source_id;
DELETE l FROM t_cluster_link l JOIN d10p_source_ids p ON p.id=l.feedback_source_id;
DELETE a FROM t_feedback_ai a JOIN d10p_source_ids p ON p.id=a.source_id;
DELETE c FROM t_channel_sdk c JOIN d10p_source_ids p ON p.id=c.source_id;
DELETE c FROM t_channel_jira c JOIN d10p_source_ids p ON p.id=c.source_id;
DELETE c FROM t_channel_quality c JOIN d10p_source_ids p ON p.id=c.source_id;
DELETE c FROM t_channel_redblack c JOIN d10p_source_ids p ON p.id=c.source_id;
DELETE i FROM t_feedback_identity i JOIN d10p_source_ids p ON p.id=i.source_id;
DELETE c FROM t_feedback_content c JOIN d10p_source_ids p ON p.id=c.source_id;
UPDATE t_sdk_ingest i JOIN d10p_source_ids p ON p.id=i.feedback_source_id SET i.feedback_source_id=NULL;
DELETE s FROM t_feedback_source s JOIN d10p_source_ids p ON p.id=s.id;
DELETE FROM t_sdk_ingest WHERE client_feedback_id LIKE 'D10P-%';
DELETE FROM t_cluster WHERE cluster_name LIKE CONCAT(@d10p_prefix,'-CL-%');
DROP TEMPORARY TABLE d10p_source_ids;

SELECT
 (SELECT COUNT(*) FROM t_feedback_source WHERE source_record_id LIKE 'D10P-%' OR source_record_id LIKE 'D10P03-%') remaining_sources,
 (SELECT COUNT(*) FROM t_sdk_ingest WHERE client_feedback_id LIKE 'D10P-%') remaining_sdk_ingest,
 (SELECT COUNT(*) FROM t_cluster WHERE cluster_name LIKE CONCAT(@d10p_prefix,'-CL-%')) remaining_clusters;
