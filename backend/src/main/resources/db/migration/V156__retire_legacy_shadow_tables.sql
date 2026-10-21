-- Final physical retirement after production read/write cutover, reconciliation,
-- full regression, and an independently restored V155 recovery snapshot.
-- These foreign keys exist only inside the retirement set; removing them first
-- makes the DROP order explicit without disabling global FK enforcement.
ALTER TABLE t_alert_escalation_target
  DROP FOREIGN KEY fk_escalation_target_chain;
ALTER TABLE t_alert_rule_contract_link
  DROP FOREIGN KEY fk_rule_contract_contract,
  DROP FOREIGN KEY fk_rule_contract_rule;
ALTER TABLE t_masking_evaluation_run
  DROP FOREIGN KEY fk_masking_run_dataset,
  DROP FOREIGN KEY fk_masking_run_release;
ALTER TABLE t_masking_rule_release
  DROP FOREIGN KEY fk_masking_release_run;
ALTER TABLE t_rca_orchestration_step
  DROP FOREIGN KEY fk_rca_step_task;

DROP TABLE
  t_alert_data_contract,
  t_alert_delivery_attempt,
  t_alert_delivery_evidence,
  t_alert_drill_instance,
  t_alert_escalation_chain,
  t_alert_escalation_target,
  t_alert_lifecycle_event,
  t_alert_metric_hourly_bucket,
  t_alert_notification_policy,
  t_alert_rule_acceptance_assessment,
  t_alert_rule_catalog,
  t_alert_rule_contract_link,
  t_alert_rule_input_quality_snapshot,
  t_alert_rule_test_run,
  t_app_version_release_fact,
  t_attachment_access_grant,
  t_cluster_review_decision_event,
  t_contact_access_audit,
  t_data_freshness_sla,
  t_data_freshness_sla_history,
  t_data_processing_version,
  t_dedup_review_event,
  t_external_security_event_receipt,
  t_field_conversion_history,
  t_idempotency_conflict_event,
  t_import_quality_snapshot,
  t_import_upload_session,
  t_in_app_notification_receipt,
  t_knowledge_usage_event,
  t_masking_evaluation_dataset,
  t_masking_evaluation_run,
  t_masking_review_sla_policy,
  t_masking_rule_release,
  t_metric_definition,
  t_ner_allowlist,
  t_notification_provider_acceptance,
  t_notification_provider_receipt,
  t_rca_orchestration_step,
  t_rca_orchestration_task,
  t_report_ai_health,
  t_report_generation_key,
  t_report_generation_task,
  t_report_schedule_run,
  t_sdk_request_telemetry,
  t_security_signal_event,
  t_system_audit_action_catalog,
  t_system_config_catalog;
