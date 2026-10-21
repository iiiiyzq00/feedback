-- The legacy hourly bucket table had a natural bucket identity. Preserve that
-- identity after consolidation so the scheduled compact upsert cannot append
-- duplicate buckets on every refresh or across multiple application nodes.
DELETE older
FROM t_alert_occurrence older
JOIN t_alert_occurrence newer
  ON newer.occurrence_type='HOURLY_BASELINE'
 AND older.occurrence_type='HOURLY_BASELINE'
 AND newer.metric_code=older.metric_code
 AND newer.dimension_key=older.dimension_key
 AND newer.evaluation_time=older.evaluation_time
 AND newer.id>older.id;

ALTER TABLE t_alert_occurrence
  ADD COLUMN hourly_baseline_key VARCHAR(255) COLLATE utf8mb4_0900_as_cs
    GENERATED ALWAYS AS (
      CASE WHEN occurrence_type='HOURLY_BASELINE' THEN
        CONCAT(metric_code,':',dimension_key,':',DATE_FORMAT(evaluation_time,'%Y%m%d%H%i%s%f'))
      ELSE NULL END
    ) STORED,
  ADD UNIQUE KEY uk_occurrence_hourly_baseline(hourly_baseline_key);
