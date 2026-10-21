ALTER TABLE t_channel_sdk
  ADD COLUMN app_internal_logs TEXT DEFAULT NULL AFTER storage_available_mb,
  ADD COLUMN crash_stack TEXT DEFAULT NULL AFTER app_internal_logs,
  ADD COLUMN account_context JSON DEFAULT NULL AFTER crash_stack,
  ADD COLUMN course_id VARCHAR(100) DEFAULT NULL AFTER account_context,
  ADD COLUMN question_id VARCHAR(100) DEFAULT NULL AFTER course_id,
  ADD COLUMN order_id VARCHAR(100) DEFAULT NULL AFTER question_id,
  ADD COLUMN page_route VARCHAR(500) DEFAULT NULL AFTER order_id,
  ADD COLUMN business_id VARCHAR(100) DEFAULT NULL AFTER page_route,
  ADD COLUMN api_request_summary TEXT DEFAULT NULL AFTER business_id,
  ADD COLUMN state_machine JSON DEFAULT NULL AFTER api_request_summary,
  ADD COLUMN custom_events JSON DEFAULT NULL AFTER state_machine,
  ADD COLUMN private_file_manifest JSON DEFAULT NULL AFTER custom_events;

UPDATE t_channel_sdk
SET app_internal_logs = JSON_UNQUOTE(JSON_EXTRACT(simulated_context, '$.app_internal_logs')),
    crash_stack = JSON_UNQUOTE(JSON_EXTRACT(simulated_context, '$.crash_stack')),
    account_context = JSON_EXTRACT(simulated_context, '$.account_context'),
    course_id = JSON_UNQUOTE(JSON_EXTRACT(simulated_context, '$.course_id')),
    question_id = JSON_UNQUOTE(JSON_EXTRACT(simulated_context, '$.question_id')),
    order_id = JSON_UNQUOTE(JSON_EXTRACT(simulated_context, '$.order_id')),
    page_route = JSON_UNQUOTE(JSON_EXTRACT(simulated_context, '$.page_route')),
    business_id = JSON_UNQUOTE(JSON_EXTRACT(simulated_context, '$.business_id')),
    api_request_summary = JSON_UNQUOTE(JSON_EXTRACT(simulated_context, '$.api_request_summary')),
    state_machine = JSON_EXTRACT(simulated_context, '$.state_machine'),
    custom_events = JSON_EXTRACT(simulated_context, '$.custom_events'),
    private_file_manifest = JSON_EXTRACT(simulated_context, '$.private_file_manifest')
WHERE simulated_context IS NOT NULL;

ALTER TABLE t_channel_sdk DROP COLUMN simulated_context;

UPDATE t_import_template
SET is_active = 0, updated_at = CURRENT_TIMESTAMP(3)
WHERE source_channel = 'SDK' AND is_active = 1;

INSERT INTO t_import_template(
  template_name, source_channel, version, schema_version, is_active,
  header_columns, field_mappings
) VALUES (
  'SDK模拟数据导入模板', 'SDK', 'v1.1.0', 'sdk-sim-import-v1', 1,
  JSON_ARRAY(
    '问题编号','问题标题','问题描述','发生时间','应用标识','应用包名','应用版本','系统版本','网络类型','设备型号',
    '屏幕方向','应用会话ID','电量百分比','可用存储MB','触发方式','输入方式','功能模块','标准问题类型',
    '应用内部日志','崩溃堆栈','账号上下文','课程ID','题目ID','订单ID','页面路由','业务ID','接口请求摘要',
    '状态机','自定义埋点','私有文件清单'
  ),
  JSON_OBJECT(
    'source_record_id','问题编号','title','问题标题','description','问题描述','source_event_time','发生时间',
    'app_id','应用标识','package_name','应用包名','app_version','应用版本','os_version','系统版本',
    'network_type','网络类型','device_model','设备型号','screen_orientation','屏幕方向','app_session_id','应用会话ID',
    'battery_level','电量百分比','storage_available_mb','可用存储MB','trigger_type','触发方式','input_methods','输入方式',
    'function_module','功能模块','standard_problem_type','标准问题类型','app_internal_logs','应用内部日志',
    'crash_stack','崩溃堆栈','account_context','账号上下文','course_id','课程ID','question_id','题目ID',
    'order_id','订单ID','page_route','页面路由','business_id','业务ID','api_request_summary','接口请求摘要',
    'state_machine','状态机','custom_events','自定义埋点','private_file_manifest','私有文件清单'
  )
);
