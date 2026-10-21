-- 只删除因夹具签名（XC-nn）而被拆开的 AI 辅导崩溃簇。
-- 在后端停止、且外部索引 DELETE 任务完成之后再执行。
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;
START TRANSACTION;

CREATE TEMPORARY TABLE tmp_bad_clusters (id BIGINT PRIMARY KEY);
INSERT INTO tmp_bad_clusters
SELECT id FROM t_cluster
WHERE function_module='AI_TUTOR' AND issue_category='BUG'
  AND error_signature REGEXP '^XC-[0-9]+$';

CREATE TEMPORARY TABLE tmp_affected_sources (id BIGINT PRIMARY KEY);
INSERT INTO tmp_affected_sources
SELECT DISTINCT feedback_source_id FROM t_cluster_link
WHERE cluster_id IN (SELECT id FROM tmp_bad_clusters);

CREATE TEMPORARY TABLE tmp_bad_alerts (id BIGINT PRIMARY KEY);
INSERT INTO tmp_bad_alerts
SELECT id FROM t_alert_instance
WHERE cluster_id IN (SELECT id FROM tmp_bad_clusters);

CREATE TEMPORARY TABLE tmp_bad_incidents (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO tmp_bad_incidents
SELECT DISTINCT incident_id FROM t_incident_alert_link
WHERE alert_instance_id IN (SELECT id FROM tmp_bad_alerts);
INSERT IGNORE INTO tmp_bad_incidents
SELECT DISTINCT incident_id FROM t_cluster_root_cause
WHERE cluster_id IN (SELECT id FROM tmp_bad_clusters) AND incident_id IS NOT NULL;

DELETE FROM t_alert_notification
WHERE alert_instance_id IN (SELECT id FROM tmp_bad_alerts)
   OR incident_id IN (SELECT id FROM tmp_bad_incidents);
DELETE FROM t_alert_occurrence WHERE instance_id IN (SELECT id FROM tmp_bad_alerts);
DELETE FROM t_incident_alert_link
WHERE alert_instance_id IN (SELECT id FROM tmp_bad_alerts)
   OR incident_id IN (SELECT id FROM tmp_bad_incidents);
DELETE k FROM t_ai_knowledge k JOIN t_root_cause_analysis r ON r.id=k.source_rca_id
WHERE r.incident_id IN (SELECT id FROM tmp_bad_incidents);
DELETE FROM t_cluster_root_cause
WHERE cluster_id IN (SELECT id FROM tmp_bad_clusters)
   OR incident_id IN (SELECT id FROM tmp_bad_incidents);
DELETE FROM t_root_cause_analysis WHERE incident_id IN (SELECT id FROM tmp_bad_incidents);
DELETE FROM t_alert_instance WHERE id IN (SELECT id FROM tmp_bad_alerts);
DELETE i FROM t_incident i
WHERE i.id IN (SELECT id FROM tmp_bad_incidents)
  AND NOT EXISTS (SELECT 1 FROM t_incident_alert_link l WHERE l.incident_id=i.id);

DELETE FROM t_ai_task
WHERE record_type='INDEX_OUTBOX'
  AND JSON_UNQUOTE(JSON_EXTRACT(input_snapshot,'$.entityType'))='CLUSTER'
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(input_snapshot,'$.entityId')) AS UNSIGNED)
      IN (SELECT id FROM tmp_bad_clusters);
DELETE FROM t_cluster_link WHERE cluster_id IN (SELECT id FROM tmp_bad_clusters);
DELETE FROM t_cluster WHERE id IN (SELECT id FROM tmp_bad_clusters);

SELECT COUNT(*) AS affected_sources FROM tmp_affected_sources;
SELECT COUNT(*) AS removed_clusters FROM tmp_bad_clusters;
COMMIT;
SET FOREIGN_KEY_CHECKS=1;
