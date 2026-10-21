-- 确定性的八天演示时间线，按渠道与严重程度均衡分布。
SET NAMES utf8mb4;
START TRANSACTION;

CREATE TEMPORARY TABLE tmp_feedback_schedule (
  id BIGINT PRIMARY KEY,
  event_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  ingest_time DATETIME(3) NOT NULL
);

INSERT INTO tmp_feedback_schedule(id,event_time,update_time,ingest_time)
SELECT id,event_time,
       TIMESTAMPADD(SECOND,3600+MOD(id*53,3600),event_time) update_time,
       TIMESTAMPADD(SECOND,4500+MOD(id*53,3600)+MOD(id*29,1800),event_time) ingest_time
FROM (
  SELECT id,
         TIMESTAMP(
           DATE_ADD(DATE('2026-07-29'),INTERVAL MOD(seq-1,8) DAY),
           SEC_TO_TIME(7*3600+MOD(id*137,7*3600))
         ) event_time
  FROM (
    SELECT s.id,
           ROW_NUMBER() OVER (
             PARTITION BY s.source_channel,COALESCE(c.severity_level,'UNKNOWN')
             ORDER BY s.id
           ) seq
    FROM t_feedback_source s
    JOIN t_feedback_content c ON c.source_id=s.id
    WHERE s.import_batch_id IN (1,2,3,4)
  ) ranked
) events;

UPDATE t_feedback_source s JOIN tmp_feedback_schedule x ON x.id=s.id
SET s.source_event_time=x.event_time,
    s.source_update_time=x.update_time,
    s.ingest_time=x.ingest_time,
    s.created_at=x.ingest_time,
    s.updated_at=x.ingest_time;

UPDATE t_feedback_content c JOIN tmp_feedback_schedule x ON x.id=c.source_id
SET c.created_at=x.ingest_time,c.updated_at=x.ingest_time;

UPDATE t_feedback_ai a JOIN tmp_feedback_schedule x ON x.id=a.source_id
SET a.ai_processed_at=IF(a.ai_processed_at IS NULL,NULL,TIMESTAMPADD(MINUTE,5,x.ingest_time)),
    a.reviewed_at=IF(a.reviewed_at IS NULL,NULL,TIMESTAMPADD(MINUTE,10,x.ingest_time)),
    a.embedding_generated_at=IF(a.embedding_generated_at IS NULL,NULL,TIMESTAMPADD(MINUTE,8,x.ingest_time)),
    a.created_at=x.ingest_time,
    a.updated_at=TIMESTAMPADD(MINUTE,10,x.ingest_time);

UPDATE t_channel_sdk d JOIN tmp_feedback_schedule x ON x.id=d.source_id
SET d.created_at=x.ingest_time;

UPDATE t_channel_jira d JOIN tmp_feedback_schedule x ON x.id=d.source_id
SET d.jira_created_at=x.event_time,
    d.jira_updated_at=x.update_time,
    d.last_viewed_at=IF(d.last_viewed_at IS NULL,NULL,x.update_time),
    d.resolved_at=IF(d.resolved_at IS NULL,NULL,x.update_time),
    d.due_date=IF(d.due_date IS NULL,NULL,DATE(x.update_time)),
    d.created_at=x.ingest_time;

UPDATE t_channel_quality d JOIN tmp_feedback_schedule x ON x.id=d.source_id
SET d.merchant_return_apply_date=IF(d.merchant_return_apply_date IS NULL,NULL,x.event_time),
    d.actual_shipping_date=IF(d.actual_shipping_date IS NULL,NULL,DATE(x.update_time)),
    d.created_at=x.ingest_time;

UPDATE t_channel_redblack d JOIN tmp_feedback_schedule x ON x.id=d.source_id
SET d.pub_time=x.event_time,
    d.create_time=IF(d.create_time IS NULL,NULL,x.event_time),
    d.update_time=IF(d.update_time IS NULL,NULL,x.update_time),
    d.push_time=IF(d.push_time IS NULL,NULL,x.update_time),
    d.created_at=x.ingest_time;

COMMIT;
