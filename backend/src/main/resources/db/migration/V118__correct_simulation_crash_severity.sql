-- simulation-repair-v1 evaluated the REDBLACK praise branch before the explicit
-- cross-channel crash branch. That produced fault text with S4, which violates
-- severity-policy-v1 (S4 is pure praise with no fault impact).

UPDATE t_feedback_content c
JOIN t_feedback_source s ON s.id=c.source_id
SET c.severity_level='S1',
    c.issue_category='BUG',
    c.problem_type='CRASH',
    c.user_sentiment='NEGATIVE',
    c.mapping_rule_version='simulation-repair-v2',
    c.updated_at=NOW(3)
WHERE c.mapping_rule_version='simulation-repair-v1'
  AND s.source_channel='REDBLACK'
  AND c.severity_level='S4'
  AND c.problem_type='CRASH'
  AND c.issue_category='BUG'
  AND (c.title LIKE '%闪退%' OR c.description LIKE '%闪退%');

UPDATE t_feedback_ai a
JOIN t_feedback_content c ON c.source_id=a.source_id
SET a.ai_category='BUG',
    a.ai_severity='S1',
    a.ai_explanation='模拟数据口径纠偏：闪退导致核心功能不可用，不满足S4纯正向无故障定义；S1按统一门禁进入人工复核。',
    a.review_status='PENDING',
    a.reviewed_by=NULL,
    a.reviewed_at=NULL,
    a.updated_at=NOW(3)
WHERE c.mapping_rule_version='simulation-repair-v2'
  AND c.problem_type='CRASH'
  AND c.issue_category='BUG'
  AND c.severity_level='S1'
  AND (c.title LIKE '%闪退%' OR c.description LIKE '%闪退%')
  AND a.ai_severity='S4';

INSERT INTO t_system_audit_log(user_id,operation,target_type,target_id,operation_detail)
VALUES(NULL,'SIMULATION_SEVERITY_POLICY_CORRECTION','CHANNEL_DATA','REDBLACK_CROSS_CHANNEL_CRASH',
       JSON_OBJECT('fromSeverity','S4','toSeverity','S1','policy','severity-policy-v1',
                   'mappingVersion','simulation-repair-v2','reason','故障文本不得归为S4'));
