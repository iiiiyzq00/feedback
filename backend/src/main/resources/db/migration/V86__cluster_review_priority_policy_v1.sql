-- cluster-review-priority-v1: priority schedules review work; it is not a second severity scale.
INSERT INTO t_system_config_catalog(config_key,value_type,min_value,max_value,owner_module,description) VALUES
('cluster.review-hotspot-min-channels','INTEGER',2,20,'CLUSTER','跨渠道热点进入P2所需的最少有效渠道数'),
('cluster.review-hotspot-min-feedback','INTEGER',2,100000,'CLUSTER','跨渠道热点进入P2所需的候选簇最少反馈量'),
('cluster.review-sla-p1-hours','INTEGER',1,72,'CLUSTER','P1归簇边界审核时限（小时）'),
('cluster.review-sla-p2-business-days','INTEGER',1,30,'CLUSTER','P2归簇边界审核时限（工作日）'),
('cluster.review-sla-p3-business-days','INTEGER',1,30,'CLUSTER','P3归簇边界审核时限（工作日）')
ON DUPLICATE KEY UPDATE description=VALUES(description),min_value=VALUES(min_value),max_value=VALUES(max_value);

INSERT INTO t_system_config(config_key,config_version,config_value,config_desc,is_active,change_set_id,change_reason)
SELECT defaults.config_key,'v1',CAST(defaults.config_value AS JSON),defaults.config_desc,1,UUID(),'V86建立归簇审核优先级基线'
FROM (
  SELECT 'cluster.review-hotspot-min-channels' config_key,'2' config_value,'跨渠道热点最少有效渠道数' config_desc UNION ALL
  SELECT 'cluster.review-hotspot-min-feedback','5','跨渠道热点最少反馈量' UNION ALL
  SELECT 'cluster.review-sla-p1-hours','4','P1归簇审核时限（小时）' UNION ALL
  SELECT 'cluster.review-sla-p2-business-days','1','P2归簇审核时限（工作日）' UNION ALL
  SELECT 'cluster.review-sla-p3-business-days','3','P3归簇审核时限（工作日）'
) defaults
WHERE NOT EXISTS (
  SELECT 1 FROM t_system_config current_config
  WHERE current_config.config_key=defaults.config_key AND current_config.is_active=1
);

-- Only pending, active candidate work is recalculated. Completed review evidence is immutable.
CREATE TEMPORARY TABLE tmp_cluster_review_priority_v1 (
  feedback_source_id BIGINT PRIMARY KEY,
  priority VARCHAR(2) NOT NULL
);

INSERT INTO tmp_cluster_review_priority_v1(feedback_source_id,priority)
SELECT l.feedback_source_id,
       CASE
         WHEN MAX(UPPER(c.severity_level) IN ('S0','S1'))=1 THEN 'P1'
         WHEN MAX(UPPER(c.severity_level)='S2')=1
           OR MAX(cluster.cluster_status='RESOLVED')=1
           OR (MAX(cluster.total_record_count>=5 AND JSON_LENGTH(COALESCE(cluster.channel_distribution,JSON_OBJECT()))>=2)=1
               AND ABS(MAX(CASE WHEN l.candidate_rank=1 THEN l.match_similarity END)-
                       MAX(CASE WHEN l.candidate_rank=2 THEN l.match_similarity END))<=0.03)
           THEN 'P2'
         ELSE 'P3'
       END
FROM t_cluster_link l
JOIN t_feedback_content c ON c.source_id=l.feedback_source_id
JOIN t_cluster cluster ON cluster.id=l.cluster_id
WHERE l.relation_role='CANDIDATE' AND l.review_status='PENDING' AND l.is_active=TRUE
GROUP BY l.feedback_source_id;

UPDATE t_cluster_link l
JOIN tmp_cluster_review_priority_v1 calculated ON calculated.feedback_source_id=l.feedback_source_id
SET l.review_priority=calculated.priority
WHERE l.relation_role='CANDIDATE' AND l.review_status='PENDING' AND l.is_active=TRUE;

UPDATE t_cluster_link l
SET l.review_due_at=CASE l.review_priority
      WHEN 'P1' THEN DATE_ADD(l.created_at,INTERVAL 4 HOUR)
      WHEN 'P2' THEN DATE_ADD(l.created_at,INTERVAL CASE DAYOFWEEK(l.created_at) WHEN 6 THEN 3 WHEN 7 THEN 2 ELSE 1 END DAY)
      ELSE DATE_ADD(l.created_at,INTERVAL CASE DAYOFWEEK(l.created_at) WHEN 1 THEN 3 WHEN 2 THEN 3 WHEN 3 THEN 3 WHEN 4 THEN 5 WHEN 5 THEN 5 WHEN 6 THEN 5 ELSE 4 END DAY)
    END,
    l.decision_reason=CONCAT(SUBSTRING_INDEX(COALESCE(l.decision_reason,'归簇候选需人工判断'),'；',1),'；',
      CASE l.review_priority
        WHEN 'P1' THEN 'S0/S1高风险问题，需紧急确认归簇边界'
        WHEN 'P2' THEN 'S2重要问题、疑似复发或合格跨渠道竞争热点，需优先确认归簇边界'
        ELSE '一般归簇边界不确定项，按常规队列处理'
      END)
WHERE l.relation_role='CANDIDATE' AND l.review_status='PENDING' AND l.is_active=TRUE;

DROP TEMPORARY TABLE tmp_cluster_review_priority_v1;
