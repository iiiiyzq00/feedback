-- D7-E01..E16: complete Alert/Incident lifecycle, evidence and optimistic concurrency.
ALTER TABLE t_alert_instance DROP CHECK chk_instance_status;
ALTER TABLE t_alert_instance
  ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0 AFTER instance_status,
  ADD COLUMN acknowledged_at DATETIME(3) DEFAULT NULL AFTER fired_at,
  ADD COLUMN acknowledged_by BIGINT DEFAULT NULL AFTER acknowledged_at,
  ADD COLUMN status_reason VARCHAR(500) DEFAULT NULL AFTER acknowledged_by,
  ADD COLUMN status_changed_by BIGINT DEFAULT NULL AFTER status_reason,
  ADD COLUMN status_changed_at DATETIME(3) DEFAULT NULL AFTER status_changed_by,
  ADD CONSTRAINT chk_instance_status CHECK (instance_status IN ('FIRING','ACKNOWLEDGED','SUPPRESSED','RESOLVED','ARCHIVED')),
  ADD CONSTRAINT chk_alert_lifecycle_version CHECK (lifecycle_version >= 0),
  ADD CONSTRAINT chk_alert_ack_evidence CHECK (instance_status<>'ACKNOWLEDGED' OR (acknowledged_at IS NOT NULL AND acknowledged_by IS NOT NULL)),
  ADD CONSTRAINT chk_alert_terminal_time CHECK ((instance_status<>'RESOLVED' OR resolved_at IS NOT NULL) AND (instance_status<>'ARCHIVED' OR archived_at IS NOT NULL));

ALTER TABLE t_incident DROP CHECK chk_incident_status;
ALTER TABLE t_incident
  ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0 AFTER incident_status,
  ADD COLUMN investigation_started_at DATETIME(3) DEFAULT NULL AFTER incident_commander,
  ADD COLUMN resolution_summary VARCHAR(1000) DEFAULT NULL AFTER resolved_at,
  ADD COLUMN closure_summary VARCHAR(1000) DEFAULT NULL AFTER closed_at,
  ADD COLUMN status_changed_by BIGINT DEFAULT NULL AFTER closure_summary,
  ADD COLUMN status_reason VARCHAR(500) DEFAULT NULL AFTER status_changed_by;

-- Historical terminal incidents predate mandatory operator evidence. Preserve them and
-- make the provenance explicit before enabling the stricter lifecycle constraints.
UPDATE t_incident
SET resolution_summary='LEGACY_MIGRATION: terminal incident created before D7 lifecycle evidence enforcement'
WHERE incident_status IN ('RESOLVED','CLOSED','ARCHIVED') AND resolution_summary IS NULL;

UPDATE t_incident
SET closed_at=COALESCE(closed_at,resolved_at),
    closure_summary=COALESCE(closure_summary,'LEGACY_MIGRATION: closed before D7 lifecycle evidence enforcement')
WHERE incident_status IN ('CLOSED','ARCHIVED');

ALTER TABLE t_incident
  ADD CONSTRAINT chk_incident_status CHECK (incident_status IN ('OPEN','INVESTIGATING','RESOLVED','CLOSED','ARCHIVED')),
  ADD CONSTRAINT chk_incident_lifecycle_version CHECK (lifecycle_version >= 0),
  ADD CONSTRAINT chk_incident_resolve_evidence CHECK (incident_status NOT IN ('RESOLVED','CLOSED','ARCHIVED') OR (resolved_at IS NOT NULL AND resolution_summary IS NOT NULL)),
  ADD CONSTRAINT chk_incident_close_evidence CHECK (incident_status NOT IN ('CLOSED','ARCHIVED') OR (closed_at IS NOT NULL AND closure_summary IS NOT NULL)),
  ADD CONSTRAINT chk_incident_time_order CHECK (closed_at IS NULL OR (resolved_at IS NOT NULL AND closed_at >= resolved_at)),
  ADD CONSTRAINT chk_incident_archive_time CHECK (incident_status<>'ARCHIVED' OR archived_at IS NOT NULL);

CREATE TABLE t_alert_lifecycle_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  aggregate_type VARCHAR(20) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  from_status VARCHAR(20) NOT NULL,
  to_status VARCHAR(20) NOT NULL,
  lifecycle_version BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  evidence JSON DEFAULT NULL,
  changed_by BIGINT DEFAULT NULL,
  changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_lifecycle_aggregate_version (aggregate_type,aggregate_id,lifecycle_version),
  KEY idx_lifecycle_timeline (aggregate_type,aggregate_id,changed_at),
  CONSTRAINT chk_lifecycle_aggregate CHECK (aggregate_type IN ('ALERT','INCIDENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='告警和事故单不可变状态迁移证据';
