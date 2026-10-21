ALTER TABLE t_feedback_source
  ADD COLUMN fault_occurred_at DATETIME(3) DEFAULT NULL AFTER source_event_time,
  ADD COLUMN fault_time_raw VARCHAR(255) DEFAULT NULL AFTER fault_occurred_at,
  ADD COLUMN fault_time_precision VARCHAR(20) DEFAULT NULL AFTER fault_time_raw,
  ADD COLUMN fault_time_source VARCHAR(40) DEFAULT NULL AFTER fault_time_precision,
  ADD COLUMN fault_time_resolution VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER fault_time_source,
  ADD KEY idx_source_channel_fault_time(source_channel,fault_occurred_at),
  ADD CONSTRAINT chk_fault_time_precision CHECK(fault_time_precision IS NULL OR fault_time_precision IN
    ('SECOND','MINUTE','HOUR','DAY','RANGE','APPROXIMATE')),
  ADD CONSTRAINT chk_fault_time_resolution CHECK(fault_time_resolution IN
    ('PENDING','RESOLVED','MISSING','UNPARSEABLE','NOT_APPLICABLE'));

-- SDK source_event_time is explicitly supplied by the client as the occurrence time.
UPDATE t_feedback_source
SET fault_occurred_at=source_event_time,fault_time_raw=DATE_FORMAT(source_event_time,'%Y-%m-%d %H:%i:%s'),
    fault_time_precision='SECOND',fault_time_source='SDK_CLIENT',fault_time_resolution='RESOLVED'
WHERE source_channel='SDK';

-- Red/black-list records describe publications rather than fault occurrences.
UPDATE t_feedback_source
SET fault_time_source='REDBLACK_NOT_APPLICABLE',fault_time_resolution='NOT_APPLICABLE'
WHERE source_channel='REDBLACK';

-- Jira, Station and Quality descriptions require the governed application parser. Keeping them
-- PENDING makes the startup backfill idempotent and prevents creation/application/publication time
-- from being presented as a fault occurrence.
