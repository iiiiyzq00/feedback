-- Consolidate team membership and on-call schedules with typed lookup columns.
ALTER TABLE t_system_config
  ADD COLUMN scope_key VARCHAR(100) DEFAULT NULL AFTER owner_team,
  ADD COLUMN config_subtype VARCHAR(30) DEFAULT NULL AFTER scope_key,
  ADD COLUMN subject_user_id BIGINT DEFAULT NULL AFTER config_subtype,
  ADD COLUMN secondary_user_id BIGINT DEFAULT NULL AFTER subject_user_id,
  ADD COLUMN record_status VARCHAR(20) DEFAULT NULL AFTER secondary_user_id,
  ADD COLUMN record_version BIGINT DEFAULT NULL AFTER record_status,
  ADD KEY idx_config_team_member (config_type,scope_key,is_active,subject_user_id),
  ADD KEY idx_config_oncall_window (config_type,scope_key,config_subtype,record_status,effective_from,effective_to),
  ADD KEY idx_config_subject_user (config_type,subject_user_id,is_active);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,owner_team,scope_key,config_subtype,subject_user_id,record_status,
 record_version,is_active,created_at,updated_at)
SELECT CONCAT('TEAM_MEMBER:',id),'TEAM_MEMBER','v1',JSON_OBJECT('legacyId',id,'team',team_name,
 'userId',user_id,'role',member_role,'primary',is_primary),'team-member-v1',
 IF(is_active=1,'ACTIVE','INACTIVE'),'APPROVED',team_name,team_name,member_role,user_id,
 IF(is_active=1,'ACTIVE','INACTIVE'),1,is_active,created_at,updated_at
FROM t_team_member ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),scope_key=VALUES(scope_key),
 config_subtype=VALUES(config_subtype),subject_user_id=VALUES(subject_user_id),record_status=VALUES(record_status),
 is_active=VALUES(is_active),updated_at=VALUES(updated_at);

INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,owner_team,scope_key,config_subtype,subject_user_id,secondary_user_id,
 record_status,record_version,effective_from,effective_to,is_active,created_by,created_at,updated_at)
SELECT CONCAT('ONCALL_SCHEDULE:',id),'ONCALL_SCHEDULE','v1',JSON_OBJECT('legacyId',id,'team',team_name,
 'name',schedule_name,'type',schedule_type,'userId',oncall_user_id,'escalationUserId',escalation_user_id),
 'oncall-schedule-v1',schedule_status,'APPROVED',team_name,team_name,schedule_type,oncall_user_id,
 escalation_user_id,schedule_status,version,oncall_start,oncall_end,schedule_status='ACTIVE',created_by,created_at,updated_at
FROM t_oncall_schedule ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),scope_key=VALUES(scope_key),
 config_subtype=VALUES(config_subtype),subject_user_id=VALUES(subject_user_id),secondary_user_id=VALUES(secondary_user_id),
 record_status=VALUES(record_status),record_version=VALUES(record_version),effective_from=VALUES(effective_from),
 effective_to=VALUES(effective_to),is_active=VALUES(is_active),updated_at=VALUES(updated_at);

CREATE TRIGGER trg_team_member_config_insert AFTER INSERT ON t_team_member FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,owner_team,scope_key,config_subtype,subject_user_id,record_status,
 record_version,is_active,created_at,updated_at)
VALUES(CONCAT('TEAM_MEMBER:',NEW.id),'TEAM_MEMBER','v1',JSON_OBJECT('legacyId',NEW.id,'team',NEW.team_name,
 'userId',NEW.user_id,'role',NEW.member_role,'primary',NEW.is_primary),'team-member-v1',
 IF(NEW.is_active=1,'ACTIVE','INACTIVE'),'APPROVED',NEW.team_name,NEW.team_name,NEW.member_role,NEW.user_id,
 IF(NEW.is_active=1,'ACTIVE','INACTIVE'),1,NEW.is_active,NEW.created_at,NEW.updated_at);

CREATE TRIGGER trg_team_member_config_update AFTER UPDATE ON t_team_member FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('legacyId',NEW.id,'team',NEW.team_name,'userId',NEW.user_id,
 'role',NEW.member_role,'primary',NEW.is_primary),lifecycle_status=IF(NEW.is_active=1,'ACTIVE','INACTIVE'),
 owner_team=NEW.team_name,scope_key=NEW.team_name,config_subtype=NEW.member_role,subject_user_id=NEW.user_id,
 record_status=IF(NEW.is_active=1,'ACTIVE','INACTIVE'),is_active=NEW.is_active,updated_at=NEW.updated_at
WHERE config_key=CONCAT('TEAM_MEMBER:',OLD.id) AND config_version='v1';

CREATE TRIGGER trg_team_member_config_delete AFTER DELETE ON t_team_member FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('TEAM_MEMBER:',OLD.id) AND config_version='v1';

CREATE TRIGGER trg_oncall_config_insert AFTER INSERT ON t_oncall_schedule FOR EACH ROW
INSERT INTO t_system_config(config_key,config_type,config_version,config_value,config_schema_version,
 lifecycle_status,approval_status,owner_team,scope_key,config_subtype,subject_user_id,secondary_user_id,
 record_status,record_version,effective_from,effective_to,is_active,created_by,created_at,updated_at)
VALUES(CONCAT('ONCALL_SCHEDULE:',NEW.id),'ONCALL_SCHEDULE','v1',JSON_OBJECT('legacyId',NEW.id,'team',NEW.team_name,
 'name',NEW.schedule_name,'type',NEW.schedule_type,'userId',NEW.oncall_user_id,'escalationUserId',NEW.escalation_user_id),
 'oncall-schedule-v1',NEW.schedule_status,'APPROVED',NEW.team_name,NEW.team_name,NEW.schedule_type,
 NEW.oncall_user_id,NEW.escalation_user_id,NEW.schedule_status,NEW.version,NEW.oncall_start,NEW.oncall_end,
 NEW.schedule_status='ACTIVE',NEW.created_by,NEW.created_at,NEW.updated_at);

CREATE TRIGGER trg_oncall_config_update AFTER UPDATE ON t_oncall_schedule FOR EACH ROW
UPDATE t_system_config SET config_value=JSON_OBJECT('legacyId',NEW.id,'team',NEW.team_name,'name',NEW.schedule_name,
 'type',NEW.schedule_type,'userId',NEW.oncall_user_id,'escalationUserId',NEW.escalation_user_id),
 lifecycle_status=NEW.schedule_status,owner_team=NEW.team_name,scope_key=NEW.team_name,
 config_subtype=NEW.schedule_type,subject_user_id=NEW.oncall_user_id,secondary_user_id=NEW.escalation_user_id,
 record_status=NEW.schedule_status,record_version=NEW.version,effective_from=NEW.oncall_start,
 effective_to=NEW.oncall_end,is_active=NEW.schedule_status='ACTIVE',updated_at=NEW.updated_at
WHERE config_key=CONCAT('ONCALL_SCHEDULE:',OLD.id) AND config_version='v1';

CREATE TRIGGER trg_oncall_config_delete AFTER DELETE ON t_oncall_schedule FOR EACH ROW
DELETE FROM t_system_config WHERE config_key=CONCAT('ONCALL_SCHEDULE:',OLD.id) AND config_version='v1';
