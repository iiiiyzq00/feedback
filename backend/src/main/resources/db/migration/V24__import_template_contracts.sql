INSERT INTO t_import_template(template_name,source_channel,version,schema_version,is_active,header_columns,field_mappings)
SELECT 'Jira标准导入模板','JIRA','v1.0.0','import-contract-v1',1,
  JSON_ARRAY('问题编号','问题标题','问题描述','创建时间','更新时间','状态','优先级','项目Key','经办人','报告人'),
  JSON_OBJECT('source_record_id','问题编号','title','问题标题','description','问题描述','source_event_time','创建时间','source_update_time','更新时间','status','状态','priority','优先级','project_key','项目Key','assignee','经办人','reporter','报告人')
WHERE NOT EXISTS (SELECT 1 FROM t_import_template WHERE source_channel='JIRA' AND is_active=1);

INSERT INTO t_import_template(template_name,source_channel,version,schema_version,is_active,header_columns,field_mappings)
SELECT '质量平台标准导入模板','QUALITY','v1.0.0','import-contract-v1',1,
  JSON_ARRAY('问题编号','问题标题','问题描述','发生时间','维修区域','区域','申请类型','换货申请','退货原因','发货日期'),
  JSON_OBJECT('source_record_id','问题编号','title','问题标题','description','问题描述','source_event_time','发生时间','repair_region','维修区域','district','区域','application_type','申请类型','exchange_request','换货申请','return_reason','退货原因','shipping_date','发货日期')
WHERE NOT EXISTS (SELECT 1 FROM t_import_template WHERE source_channel='QUALITY' AND is_active=1);

INSERT INTO t_import_template(template_name,source_channel,version,schema_version,is_active,header_columns,field_mappings)
SELECT '红黑榜标准导入模板','REDBLACK','v1.0.0','import-contract-v1',1,
  JSON_ARRAY('问题编号','问题标题','问题描述','发布时间','原始ID','UID','作者','链接','来源平台','发布类型','板块','红黑榜类型','是否删除'),
  JSON_OBJECT('source_record_id','问题编号','title','问题标题','description','问题描述','source_event_time','发布时间','original_id','原始ID','uid','UID','author','作者','url','链接','source_platform','来源平台','pub_type','发布类型','section','板块','emotion','红黑榜类型','is_deleted','是否删除')
WHERE NOT EXISTS (SELECT 1 FROM t_import_template WHERE source_channel='REDBLACK' AND is_active=1);
