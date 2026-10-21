-- 把保留的四渠道演示数据集归一到数据库的当天。
-- 保留每个时间戳的「当日时刻」与可空性。
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE t_feedback_source
SET source_event_time = TIMESTAMP(CURDATE(), TIME(source_event_time)),
    source_update_time = TIMESTAMP(CURDATE(), TIME(source_update_time)),
    ingest_time = TIMESTAMP(CURDATE(), TIME(ingest_time)),
    created_at = TIMESTAMP(CURDATE(), TIME(created_at)),
    updated_at = TIMESTAMP(CURDATE(), TIME(updated_at))
WHERE import_batch_id IN (1,2,3,4);

UPDATE t_feedback_content c JOIN t_feedback_source s ON s.id=c.source_id
SET c.created_at=TIMESTAMP(CURDATE(),TIME(c.created_at)),
    c.updated_at=TIMESTAMP(CURDATE(),TIME(c.updated_at))
WHERE s.import_batch_id IN (1,2,3,4);

UPDATE t_feedback_ai a JOIN t_feedback_source s ON s.id=a.source_id
SET a.ai_processed_at=IFNULL(TIMESTAMP(CURDATE(),TIME(a.ai_processed_at)),NULL),
    a.reviewed_at=IFNULL(TIMESTAMP(CURDATE(),TIME(a.reviewed_at)),NULL),
    a.embedding_generated_at=IFNULL(TIMESTAMP(CURDATE(),TIME(a.embedding_generated_at)),NULL),
    a.created_at=TIMESTAMP(CURDATE(),TIME(a.created_at)),
    a.updated_at=TIMESTAMP(CURDATE(),TIME(a.updated_at))
WHERE s.import_batch_id IN (1,2,3,4);

UPDATE t_channel_sdk d JOIN t_feedback_source s ON s.id=d.source_id
SET d.created_at=TIMESTAMP(CURDATE(),TIME(d.created_at))
WHERE s.import_batch_id IN (1,2,3,4);

UPDATE t_channel_jira d JOIN t_feedback_source s ON s.id=d.source_id
SET d.jira_created_at=IFNULL(TIMESTAMP(CURDATE(),TIME(d.jira_created_at)),NULL),
    d.jira_updated_at=IFNULL(TIMESTAMP(CURDATE(),TIME(d.jira_updated_at)),NULL),
    d.last_viewed_at=IFNULL(TIMESTAMP(CURDATE(),TIME(d.last_viewed_at)),NULL),
    d.resolved_at=IFNULL(TIMESTAMP(CURDATE(),TIME(d.resolved_at)),NULL),
    d.due_date=IF(d.due_date IS NULL,NULL,CURDATE()),
    d.created_at=TIMESTAMP(CURDATE(),TIME(d.created_at))
WHERE s.import_batch_id IN (1,2,3,4);

UPDATE t_channel_quality d JOIN t_feedback_source s ON s.id=d.source_id
SET d.merchant_return_apply_date=IFNULL(TIMESTAMP(CURDATE(),TIME(d.merchant_return_apply_date)),NULL),
    d.actual_shipping_date=IF(d.actual_shipping_date IS NULL,NULL,CURDATE()),
    d.created_at=TIMESTAMP(CURDATE(),TIME(d.created_at))
WHERE s.import_batch_id IN (1,2,3,4);

UPDATE t_channel_redblack d JOIN t_feedback_source s ON s.id=d.source_id
SET d.push_time=IFNULL(TIMESTAMP(CURDATE(),TIME(d.push_time)),NULL),
    d.pub_time=IFNULL(TIMESTAMP(CURDATE(),TIME(d.pub_time)),NULL),
    d.create_time=IFNULL(TIMESTAMP(CURDATE(),TIME(d.create_time)),NULL),
    d.update_time=IFNULL(TIMESTAMP(CURDATE(),TIME(d.update_time)),NULL),
    d.created_at=TIMESTAMP(CURDATE(),TIME(d.created_at))
WHERE s.import_batch_id IN (1,2,3,4);

UPDATE t_import_batch
SET quality_calculated_at=IFNULL(TIMESTAMP(CURDATE(),TIME(quality_calculated_at)),NULL),
    data_date_from=IF(data_date_from IS NULL,NULL,CURDATE()),
    data_date_to=IF(data_date_to IS NULL,NULL,CURDATE()),
    started_at=IFNULL(TIMESTAMP(CURDATE(),TIME(started_at)),NULL),
    completed_at=IFNULL(TIMESTAMP(CURDATE(),TIME(completed_at)),NULL),
    created_at=TIMESTAMP(CURDATE(),TIME(created_at)),
    updated_at=TIMESTAMP(CURDATE(),TIME(updated_at))
WHERE id IN (1,2,3,4);

-- 保留的当日时刻可能晚于当前时钟。要让每条演示
-- 记录立刻可见，只把未来时间戳挪到「现在」之前即可。
UPDATE t_feedback_source
SET source_event_time=IF(source_event_time>NOW(3),TIMESTAMPADD(SECOND,-MOD(id,600),NOW(3)),source_event_time),
    source_update_time=IF(source_update_time>NOW(3),TIMESTAMPADD(SECOND,-MOD(id,600),NOW(3)),source_update_time),
    ingest_time=IF(ingest_time>NOW(3),TIMESTAMPADD(SECOND,-MOD(id,600),NOW(3)),ingest_time)
WHERE import_batch_id IN (1,2,3,4);

UPDATE t_channel_jira d JOIN t_feedback_source s ON s.id=d.source_id
SET d.jira_created_at=IF(d.jira_created_at>NOW(3),TIMESTAMPADD(SECOND,-MOD(s.id,600),NOW(3)),d.jira_created_at),
    d.jira_updated_at=IF(d.jira_updated_at>NOW(3),TIMESTAMPADD(SECOND,-MOD(s.id,600),NOW(3)),d.jira_updated_at),
    d.last_viewed_at=IF(d.last_viewed_at>NOW(3),TIMESTAMPADD(SECOND,-MOD(s.id,600),NOW(3)),d.last_viewed_at),
    d.resolved_at=IF(d.resolved_at>NOW(3),TIMESTAMPADD(SECOND,-MOD(s.id,600),NOW(3)),d.resolved_at)
WHERE s.import_batch_id IN (1,2,3,4);

UPDATE t_channel_redblack d JOIN t_feedback_source s ON s.id=d.source_id
SET d.push_time=IF(d.push_time>NOW(3),TIMESTAMPADD(SECOND,-MOD(s.id,600),NOW(3)),d.push_time),
    d.pub_time=IF(d.pub_time>NOW(3),TIMESTAMPADD(SECOND,-MOD(s.id,600),NOW(3)),d.pub_time),
    d.create_time=IF(d.create_time>NOW(3),TIMESTAMPADD(SECOND,-MOD(s.id,600),NOW(3)),d.create_time),
    d.update_time=IF(d.update_time>NOW(3),TIMESTAMPADD(SECOND,-MOD(s.id,600),NOW(3)),d.update_time)
WHERE s.import_batch_id IN (1,2,3,4);

COMMIT;
