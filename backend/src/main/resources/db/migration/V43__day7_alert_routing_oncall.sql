ALTER TABLE t_alert_rule ADD COLUMN priority INT NOT NULL DEFAULT 100 AFTER owner_team;
ALTER TABLE t_alert_rule ADD KEY idx_alert_rule_active_priority(version_status,is_enabled,priority,id);

ALTER TABLE t_oncall_schedule
  ADD COLUMN schedule_type VARCHAR(20) NOT NULL DEFAULT 'PRIMARY' AFTER schedule_name,
  ADD COLUMN schedule_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER escalation_user_id,
  ADD COLUMN created_by BIGINT DEFAULT NULL AFTER schedule_status,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER created_by;
ALTER TABLE t_oncall_schedule
  ADD CONSTRAINT chk_oncall_type CHECK(schedule_type IN ('PRIMARY','BACKUP','TEMPORARY')),
  ADD CONSTRAINT chk_oncall_status CHECK(schedule_status IN ('ACTIVE','CANCELLED'));

UPDATE t_alert_rule r JOIN t_alert_rule_catalog c ON c.rule_code=r.rule_code
SET r.owner_team=COALESCE(r.owner_team,c.owner_team)
WHERE r.version_status='ACTIVE';
