-- Keep compact report task records aligned while legacy shadows are still used for rollback cleanup.

CREATE TRIGGER trg_report_task_compact_delete AFTER DELETE ON t_report_generation_task FOR EACH ROW
DELETE FROM t_ai_task
WHERE legacy_source_table='t_report_generation_task' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_report_key_compact_delete AFTER DELETE ON t_report_generation_key FOR EACH ROW
DELETE FROM t_ai_task
WHERE legacy_source_table='t_report_generation_key' AND legacy_source_id=OLD.id;

CREATE TRIGGER trg_report_schedule_compact_delete AFTER DELETE ON t_report_schedule_run FOR EACH ROW
DELETE FROM t_ai_task
WHERE legacy_source_table='t_report_schedule_run' AND legacy_source_id=OLD.id;

