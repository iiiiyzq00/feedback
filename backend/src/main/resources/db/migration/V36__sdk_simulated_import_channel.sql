ALTER TABLE t_import_template DROP CHECK chk_template_channel;
ALTER TABLE t_import_template
  ADD CONSTRAINT chk_template_channel CHECK (source_channel IN ('SDK','JIRA','QUALITY','REDBLACK'));

ALTER TABLE t_import_batch DROP CHECK chk_import_batch_channel;
ALTER TABLE t_import_batch
  ADD CONSTRAINT chk_import_batch_channel CHECK (source_channel IN ('SDK','JIRA','QUALITY','REDBLACK'));

ALTER TABLE t_import_upload_session DROP CHECK chk_import_upload_channel;
ALTER TABLE t_import_upload_session
  ADD CONSTRAINT chk_import_upload_channel CHECK (source_channel IN ('SDK','JIRA','QUALITY','REDBLACK'));

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_channel_sdk' AND column_name = 'integration_mode'),
  'SELECT 1',
  'ALTER TABLE t_channel_sdk ADD COLUMN integration_mode VARCHAR(30) NOT NULL DEFAULT ''EMBEDDED_SDK'' AFTER input_methods'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_channel_sdk' AND column_name = 'context_source'),
  'SELECT 1',
  'ALTER TABLE t_channel_sdk ADD COLUMN context_source VARCHAR(30) NOT NULL DEFAULT ''RUNTIME'' AFTER integration_mode'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_channel_sdk' AND column_name = 'simulated_context'),
  'SELECT 1',
  'ALTER TABLE t_channel_sdk ADD COLUMN simulated_context JSON DEFAULT NULL AFTER context_source'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO t_import_template(
  template_name,source_channel,version,schema_version,is_active,header_columns,field_mappings
) VALUES (
  'SDK模拟数据导入模板','SDK','v1.0.0','sdk-sim-import-v1',1,
  JSON_ARRAY(
    '问题编号','问题标题','问题描述','发生时间','应用标识','应用包名','应用版本','系统版本','网络类型','设备型号',
    '触发方式','输入方式','功能模块','标准问题类型','应用内部日志','崩溃堆栈','账号上下文','课程ID','题目ID','订单ID',
    '页面路由','业务ID','接口请求摘要','状态机','自定义埋点','私有文件清单'
  ),
  JSON_OBJECT(
    'source_record_id','问题编号','title','问题标题','description','问题描述','source_event_time','发生时间',
    'app_id','应用标识','package_name','应用包名','app_version','应用版本','os_version','系统版本',
    'network_type','网络类型','device_model','设备型号','trigger_type','触发方式','input_methods','输入方式',
    'function_module','功能模块','standard_problem_type','标准问题类型','app_internal_logs','应用内部日志',
    'crash_stack','崩溃堆栈','account_context','账号上下文','course_id','课程ID','question_id','题目ID',
    'order_id','订单ID','page_route','页面路由','business_id','业务ID','api_request_summary','接口请求摘要',
    'state_machine','状态机','custom_events','自定义埋点','private_file_manifest','私有文件清单'
  )
);
