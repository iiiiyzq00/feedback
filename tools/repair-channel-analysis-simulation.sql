START TRANSACTION;

SET @repair_actor=(SELECT MIN(u.id) FROM t_auth_user u JOIN t_auth_user_role ur ON ur.user_id=u.id
                   WHERE ur.role_code='ADMIN' AND u.is_active=1);

DROP TEMPORARY TABLE IF EXISTS tmp_channel_repair;
CREATE TEMPORARY TABLE tmp_channel_repair AS
SELECT s.id source_id,s.source_channel,
       CASE s.source_channel
         WHEN 'JIRA' THEN CAST(SUBSTRING_INDEX(s.source_record_id,'-',-1) AS UNSIGNED)-1000
         ELSE CAST(SUBSTRING_INDEX(s.source_record_id,'-',-1) AS UNSIGNED)
       END sample_index,
       c.title LIKE '%-XC-%' cross_channel_fixture
FROM t_feedback_source s
JOIN t_feedback_content c ON c.source_id=s.id
WHERE c.is_deleted=0 AND s.source_channel IN ('SDK','JIRA','QUALITY','REDBLACK');

UPDATE t_feedback_content c
JOIN tmp_channel_repair r ON r.source_id=c.source_id
SET c.function_module=CASE WHEN r.cross_channel_fixture THEN 'AI_TUTOR' ELSE ELT(MOD(r.sample_index,5)+1,
        'AI_TUTOR','SYNC_COURSE','WRONG_QUESTION_BOOK','CAMERA_MIC','VIDEO_PLAYBACK') END,
    c.issue_category=CASE
      WHEN r.cross_channel_fixture THEN 'BUG'
      WHEN r.source_channel='REDBLACK' AND MOD(r.sample_index,3)<>0 THEN 'PRAISE'
      ELSE ELT(MOD(r.sample_index,5)+1,'BUG','BUG','CONTENT_ERROR','HARDWARE','BUG') END,
    c.problem_type=CASE
      WHEN r.cross_channel_fixture THEN 'CRASH'
      WHEN r.source_channel='REDBLACK' AND MOD(r.sample_index,3)<>0 THEN 'OTHER'
      ELSE ELT(MOD(r.sample_index,5)+1,
        'CRASH','FREEZE','CONTENT_ERROR','MIC_FAIL','LAG') END,
    c.severity_level=CASE
      WHEN r.cross_channel_fixture THEN 'S1'
      WHEN r.source_channel='REDBLACK' AND MOD(r.sample_index,3)<>0 THEN 'S4'
      ELSE COALESCE(c.severity_level,ELT(MOD(r.sample_index,4)+1,'S1','S2','S2','S3')) END,
    c.user_sentiment=CASE
      WHEN r.cross_channel_fixture THEN 'NEGATIVE'
      WHEN r.source_channel='REDBLACK' AND MOD(r.sample_index,3)<>0 THEN 'POSITIVE'
      WHEN r.source_channel='REDBLACK' THEN 'NEGATIVE' ELSE c.user_sentiment END,
    c.title=CASE
      WHEN r.cross_channel_fixture THEN c.title
      WHEN r.source_channel='REDBLACK' AND MOD(r.sample_index,3)<>0 THEN CONCAT('红榜好评：',ELT(MOD(r.sample_index,5)+1,
        'AI助教','同步课程','错题本','麦克风','视频播放'),'使用体验良好-',r.sample_index)
      ELSE CONCAT(ELT(MOD(r.sample_index,5)+1,
        'AI助教打开后闪退','同步课程页面卡死','错题本答案错误','麦克风无法录音','视频播放明显卡顿'),'-',r.sample_index) END,
    c.description=CASE
      WHEN r.cross_channel_fixture THEN CONCAT('跨渠道模拟问题：AI助教打开后闪退；记录编号 ',r.sample_index,'。')
      WHEN r.source_channel='REDBLACK' AND MOD(r.sample_index,3)<>0 THEN CONCAT('模拟红榜评价：',ELT(MOD(r.sample_index,5)+1,
        'AI助教','同步课程','错题本','麦克风','视频播放'),'使用体验良好；记录编号 ',r.sample_index,'。')
      ELSE CONCAT('模拟业务反馈：',ELT(MOD(r.sample_index,5)+1,
        'AI助教打开后闪退','同步课程页面卡死','错题本答案错误','麦克风无法录音','视频播放明显卡顿'),'；记录编号 ',r.sample_index,'。') END,
    c.mapping_error=NULL,c.mapping_rule_version='simulation-repair-v2',c.updated_at=NOW(3);

UPDATE t_channel_jira j JOIN tmp_channel_repair r ON r.source_id=j.source_id
SET j.issue_type_raw=CASE WHEN r.cross_channel_fixture THEN 'Bug' ELSE ELT(MOD(r.sample_index,5)+1,
      'Bug','缺陷','内容错误','缺陷','Bug') END,
    j.module_raw=CASE WHEN r.cross_channel_fixture THEN 'AI_TUTOR' ELSE ELT(MOD(r.sample_index,5)+1,
      'AI_TUTOR','SYNC_COURSE','WRONG_QUESTION_BOOK','CAMERA_MIC','VIDEO_PLAYBACK') END;

UPDATE t_channel_quality q JOIN tmp_channel_repair r ON r.source_id=q.source_id
SET q.application_type_raw=CASE
      WHEN r.cross_channel_fixture THEN '维修'
      WHEN MOD(r.sample_index,5)=3 THEN '维修'
      ELSE '报修' END,
    q.fault_remark=(SELECT c.description FROM t_feedback_content c WHERE c.source_id=q.source_id);

UPDATE t_channel_redblack rb JOIN tmp_channel_repair r ON r.source_id=rb.source_id
SET rb.section_raw=CASE WHEN r.cross_channel_fixture THEN 'AI_TUTOR' ELSE ELT(MOD(r.sample_index,5)+1,
      'AI_TUTOR','SYNC_COURSE','WRONG_QUESTION_BOOK','CAMERA_MIC','VIDEO_PLAYBACK') END,
    rb.emotion_type_raw=CASE WHEN MOD(r.sample_index,3)=0 OR r.cross_channel_fixture THEN '负面' ELSE '正面' END,
    rb.headline=(SELECT c.title FROM t_feedback_content c WHERE c.source_id=rb.source_id),
    rb.content_text=(SELECT c.description FROM t_feedback_content c WHERE c.source_id=rb.source_id);

UPDATE t_cluster cl
SET cl.cluster_status='SPLIT_ARCHIVED',cl.status_changed_by=@repair_actor,
    cl.status_changed_at=NOW(3),cl.status_reason='四渠道模拟数据标准字段修复后重建问题簇',cl.updated_at=NOW(3)
WHERE cl.cluster_status IN ('ACTIVE','MITIGATING','REOPENED','RESOLVED')
  AND EXISTS(SELECT 1 FROM t_cluster_link l JOIN tmp_channel_repair r ON r.source_id=l.feedback_source_id
             WHERE l.cluster_id=cl.id AND l.is_active=1);

UPDATE t_cluster_link l JOIN tmp_channel_repair r ON r.source_id=l.feedback_source_id
SET l.is_active=0,l.review_status=CASE WHEN l.review_status='PENDING' THEN 'SKIPPED' ELSE l.review_status END
WHERE l.is_active=1;

DELETE t FROM t_ai_task t JOIN tmp_channel_repair r ON r.source_id=t.source_id
WHERE t.record_type='INVOCATION' AND t.skill_code='feedback_classify';
DELETE a FROM t_feedback_ai a JOIN tmp_channel_repair r ON r.source_id=a.source_id;

INSERT INTO t_system_audit_log(user_id,operation,target_type,target_id,operation_detail)
VALUES(NULL,'CHANNEL_SIMULATION_ANALYSIS_REPAIR','CHANNEL_DATA','SDK_JIRA_QUALITY_REDBLACK',
       JSON_OBJECT('scope','channel-derived-only','mappingVersion','simulation-repair-v2',
                   'records',(SELECT COUNT(*) FROM tmp_channel_repair),
                   'preserved','users,permissions,configs,rules,audits,non-channel business data'));

COMMIT;
