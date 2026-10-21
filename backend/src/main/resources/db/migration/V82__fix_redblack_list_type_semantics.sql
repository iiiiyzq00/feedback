-- Red/black list type is a business classification derived from the source
-- emotion field. pub_type_name is the publication form (original/repost/comment)
-- and must never be used as the red/black classification.
ALTER TABLE t_channel_redblack DROP COLUMN list_type;

ALTER TABLE t_channel_redblack
  ADD COLUMN list_type VARCHAR(20) GENERATED ALWAYS AS (
    CASE
      WHEN UPPER(REPLACE(REPLACE(TRIM(emotion_type_raw), '_', ''), ' ', ''))
           IN ('POSITIVE', '正面', '正向', '红榜') THEN 'RED'
      WHEN UPPER(REPLACE(REPLACE(TRIM(emotion_type_raw), '_', ''), ' ', ''))
           IN ('NEGATIVE', '负面', '负向', '黑榜') THEN 'BLACK'
      ELSE 'UNKNOWN'
    END
  ) STORED AFTER pub_type_name;

CREATE INDEX idx_redblack_list_type_time ON t_channel_redblack(list_type, pub_time, source_id);
