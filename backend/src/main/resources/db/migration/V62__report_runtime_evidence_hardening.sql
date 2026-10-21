ALTER TABLE t_alert_notification
  ADD UNIQUE KEY uk_report_notification_once(report_id,event_type,notify_channel);

UPDATE t_report_generation_task t
LEFT JOIN t_ai_report r ON r.id=t.report_id
SET t.report_id=NULL,t.task_status='FAILED',t.error_code='REPORT_EVIDENCE_ORPHANED',
    t.error_detail='V62 detected a task reference to a missing report',t.completed_at=COALESCE(t.completed_at,NOW(3))
WHERE t.report_id IS NOT NULL AND r.id IS NULL;

UPDATE t_report_schedule_run s
LEFT JOIN t_ai_report r ON r.id=s.report_id
SET s.report_id=NULL,s.run_status='FAILED',s.error_code='REPORT_EVIDENCE_ORPHANED',
    s.error_message='V62 detected a schedule reference to a missing report',s.finished_at=COALESCE(s.finished_at,NOW(3))
WHERE s.report_id IS NOT NULL AND r.id IS NULL;

ALTER TABLE t_report_generation_task
  ADD CONSTRAINT fk_report_task_report FOREIGN KEY(report_id) REFERENCES t_ai_report(id);

ALTER TABLE t_report_schedule_run
  ADD CONSTRAINT fk_report_schedule_report FOREIGN KEY(report_id) REFERENCES t_ai_report(id);

ALTER TABLE t_report_generation_key
  ADD CONSTRAINT fk_report_generation_key_report FOREIGN KEY(report_id) REFERENCES t_ai_report(id);
