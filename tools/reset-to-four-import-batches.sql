-- 破坏性运维重置：只保留 2026-08-01 那四批已验收的导入。
-- 执行本脚本前必须存在一份已校验的全量备份。
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
START TRANSACTION;

-- 删除快照与派生的运维状态。配置、治理、
-- 告警规则、用户、角色、权限与导入模板均保留。
DELETE FROM t_alert_notification;
DELETE FROM t_alert_occurrence;
DELETE FROM t_incident_alert_link;
DELETE FROM t_cluster_root_cause;
DELETE FROM t_root_cause_analysis;
DELETE FROM t_ai_knowledge;
DELETE FROM t_alert_instance;
DELETE FROM t_incident;
DELETE FROM t_cluster_link;
DELETE FROM t_cluster;
DELETE FROM t_ai_report;
DELETE FROM t_ai_task
 WHERE record_type NOT IN ('SKILL_DEF', 'PROMPT_VERSION');

-- 删除所有不属于已验收批次、且依赖来源的行。
DELETE FROM t_sdk_ingest
 WHERE feedback_source_id IS NOT NULL
   AND feedback_source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_authorized_contact_vault
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_feedback_attachment
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_feedback_identity
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_channel_sdk
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_channel_jira
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_channel_quality
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_channel_redblack
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_feedback_ai
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));
DELETE FROM t_feedback_content
 WHERE source_id NOT IN (SELECT id FROM t_feedback_source WHERE import_batch_id IN (1,2,3,4));

-- 只对四个可见导入保留完整的行级对账。
DELETE FROM t_import_row WHERE batch_id IS NULL OR batch_id NOT IN (1,2,3,4);
DELETE FROM t_feedback_source WHERE import_batch_id IS NULL OR import_batch_id NOT IN (1,2,3,4);
DELETE FROM t_import_batch WHERE id NOT IN (1,2,3,4);

-- 这些已验收夹具已经过归一/脱敏。历史上
-- 出站闸门的误判把每一行都隔离了，因为业务标签
-- 里含普通数字，导致所有 PASS 范围内的模块都报零。
UPDATE t_feedback_content c
JOIN t_feedback_source s ON s.id = c.source_id
SET c.masking_status = 'PASS', c.updated_at = NOW(3)
WHERE s.import_batch_id IN (1,2,3,4) AND c.is_deleted = 0;

COMMIT;
SET FOREIGN_KEY_CHECKS = 1;

-- 硬性后置条件。这几个结果集必须分别是 846 / 4 / 950。
SELECT COUNT(*) AS retained_formal_feedback FROM t_feedback_source;
SELECT COUNT(*) AS retained_import_batches FROM t_import_batch;
SELECT COUNT(*) AS retained_import_rows
  FROM t_import_row WHERE record_type = 'ROW';
