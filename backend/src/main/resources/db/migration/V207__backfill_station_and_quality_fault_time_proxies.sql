-- Current Station rows are the governed historical export imported before the realtime source was
-- connected. Its export creation time is an explicit temporary proxy, not a client event time.
UPDATE t_feedback_source
SET fault_occurred_at=source_event_time,fault_time_raw=DATE_FORMAT(source_event_time,'%Y-%m-%d %H:%i:%s'),
    fault_time_precision='SECOND',fault_time_source='STATION_HISTORY_CREATED_PROXY',
    fault_time_resolution='RESOLVED',updated_at=NOW(3)
WHERE source_channel='STATION_FEEDBACK' AND fault_time_resolution='MISSING';

-- The quality workbook has no fault occurrence field. Merchant-return application is the first
-- complete business event closest to issue recognition; dispatch/activation/shipping are not faults.
UPDATE t_feedback_source s
JOIN t_channel_quality q ON q.source_id=s.id
SET s.fault_occurred_at=q.merchant_return_apply_date,
    s.fault_time_raw=DATE_FORMAT(q.merchant_return_apply_date,'%Y-%m-%d'),
    s.fault_time_precision='DAY',s.fault_time_source='QUALITY_RETURN_APPLY_PROXY',
    s.fault_time_resolution=IF(q.merchant_return_apply_date IS NULL,'MISSING','RESOLVED'),s.updated_at=NOW(3)
WHERE s.source_channel='QUALITY';
