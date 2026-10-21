-- A feedback keeps one active MAIN, while many feedback records may converge on
-- one active problem cluster.  The persisted name key prevents the same visible
-- problem name from being created as multiple operational clusters.
ALTER TABLE t_cluster
    ADD COLUMN cluster_name_key CHAR(64) NULL AFTER cluster_name;

UPDATE t_cluster
SET cluster_name_key = SHA2(LOWER(REGEXP_REPLACE(TRIM(cluster_name), '[[:space:]]+', ' ')), 256)
WHERE cluster_name_key IS NULL;

CREATE TEMPORARY TABLE tmp_v224_cluster_name_keeper AS
SELECT cluster_name_key, id AS keeper_id
FROM (
    SELECT cluster_name_key, id,
           ROW_NUMBER() OVER (
               PARTITION BY cluster_name_key
               ORDER BY CASE cluster_status
                   WHEN 'ACTIVE' THEN 1 WHEN 'MITIGATING' THEN 2
                   WHEN 'REOPENED' THEN 3 WHEN 'RESOLVED' THEN 4 ELSE 5 END,
                   id
           ) AS row_number_in_name
    FROM t_cluster
    WHERE cluster_status IN ('ACTIVE','MITIGATING','REOPENED','RESOLVED')
) ranked
WHERE row_number_in_name = 1;

ALTER TABLE tmp_v224_cluster_name_keeper
    ADD PRIMARY KEY (cluster_name_key),
    ADD UNIQUE KEY uk_tmp_v224_keeper (keeper_id);

UPDATE t_cluster_link link_row
JOIN t_cluster duplicate_cluster ON duplicate_cluster.id = link_row.cluster_id
JOIN tmp_v224_cluster_name_keeper keeper
  ON keeper.cluster_name_key = duplicate_cluster.cluster_name_key
SET link_row.cluster_id = keeper.keeper_id
WHERE duplicate_cluster.id <> keeper.keeper_id
  AND link_row.is_active = 1
  AND duplicate_cluster.cluster_status IN ('ACTIVE','MITIGATING','REOPENED','RESOLVED');

UPDATE t_alert_instance alert_row
JOIN t_cluster duplicate_cluster ON duplicate_cluster.id = alert_row.cluster_id
JOIN tmp_v224_cluster_name_keeper keeper
  ON keeper.cluster_name_key = duplicate_cluster.cluster_name_key
SET alert_row.cluster_id = keeper.keeper_id
WHERE duplicate_cluster.id <> keeper.keeper_id
  AND duplicate_cluster.cluster_status IN ('ACTIVE','MITIGATING','REOPENED','RESOLVED');

UPDATE t_cluster duplicate_cluster
JOIN tmp_v224_cluster_name_keeper keeper
  ON keeper.cluster_name_key = duplicate_cluster.cluster_name_key
SET duplicate_cluster.cluster_status = 'MERGED',
    duplicate_cluster.merged_into_cluster_id = keeper.keeper_id,
    duplicate_cluster.lifecycle_version = duplicate_cluster.lifecycle_version + 1,
    duplicate_cluster.status_changed_by = COALESCE(duplicate_cluster.status_changed_by, 0),
    duplicate_cluster.status_changed_at = NOW(3),
    duplicate_cluster.status_reason = 'V224同名活动簇收敛',
    duplicate_cluster.total_record_count = 0,
    duplicate_cluster.channel_distribution = JSON_OBJECT(),
    duplicate_cluster.statistics_reconciled_at = NULL,
    duplicate_cluster.search_index_status = 'PENDING',
    duplicate_cluster.vector_index_status = 'PENDING'
WHERE duplicate_cluster.id <> keeper.keeper_id
  AND duplicate_cluster.cluster_status IN ('ACTIVE','MITIGATING','REOPENED','RESOLVED');

UPDATE t_cluster keeper_cluster
JOIN tmp_v224_cluster_name_keeper keeper ON keeper.keeper_id = keeper_cluster.id
LEFT JOIN (
    SELECT cluster_id, COUNT(*) AS active_main_count
    FROM t_cluster_link
    WHERE is_active = 1 AND relation_role = 'MAIN'
    GROUP BY cluster_id
) counts ON counts.cluster_id = keeper_cluster.id
SET keeper_cluster.total_record_count = COALESCE(counts.active_main_count, 0),
    keeper_cluster.statistics_reconciled_at = NULL,
    keeper_cluster.search_index_status = 'PENDING',
    keeper_cluster.vector_index_status = 'PENDING';

DROP TEMPORARY TABLE tmp_v224_cluster_name_keeper;

ALTER TABLE t_cluster
    MODIFY COLUMN cluster_name_key CHAR(64) NOT NULL,
    ADD COLUMN active_cluster_name_key CHAR(64) GENERATED ALWAYS AS (
        CASE WHEN cluster_status IN ('ACTIVE','MITIGATING','REOPENED','RESOLVED')
             THEN cluster_name_key ELSE NULL END
    ) STORED AFTER cluster_name_key,
    ADD UNIQUE KEY uk_cluster_active_name_key (active_cluster_name_key);
