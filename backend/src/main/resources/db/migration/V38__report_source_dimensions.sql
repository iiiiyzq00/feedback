ALTER TABLE t_feedback_source ADD COLUMN package_name VARCHAR(100) DEFAULT NULL AFTER app_version;
ALTER TABLE t_feedback_source ADD KEY idx_package_version_time (package_name,app_version,source_event_time);
ALTER TABLE t_channel_redblack ADD COLUMN list_type VARCHAR(50) GENERATED ALWAYS AS (pub_type_name) STORED AFTER pub_type_name;
