UPDATE t_import_template SET is_active=0 WHERE is_active=1 AND source_channel IN ('JIRA','QUALITY','REDBLACK');

INSERT INTO t_import_template(template_name,source_channel,version,schema_version,is_active,header_columns,field_mappings)
VALUES
('Jira标准导入模板','JIRA','v1.1.0','import-contract-v1',1,
 JSON_ARRAY('问题编号','问题标题','问题描述','创建时间','更新时间','状态','优先级','项目Key','经办人','报告人','业务链接','附件链接','附件SHA256','附件大小'),
 JSON_OBJECT('source_record_id','问题编号','title','问题标题','description','问题描述','source_event_time','创建时间','source_update_time','更新时间','status','状态','priority','优先级','project_key','项目Key','assignee','经办人','reporter','报告人','source_url','业务链接','attachment_urls','附件链接','attachment_sha256','附件SHA256','attachment_size','附件大小')),
('质量平台标准导入模板','QUALITY','v1.1.0','import-contract-v1',1,
 JSON_ARRAY('问题编号','问题标题','问题描述','发生时间','维修区域','区域','申请类型','换货申请','退货原因','发货日期','业务链接','附件链接','附件SHA256','附件大小'),
 JSON_OBJECT('source_record_id','问题编号','title','问题标题','description','问题描述','source_event_time','发生时间','repair_region','维修区域','district','区域','application_type','申请类型','exchange_request','换货申请','return_reason','退货原因','shipping_date','发货日期','source_url','业务链接','attachment_urls','附件链接','attachment_sha256','附件SHA256','attachment_size','附件大小')),
('红黑榜标准导入模板','REDBLACK','v1.1.0','import-contract-v1',1,
 JSON_ARRAY('问题编号','问题标题','问题描述','发布时间','原始ID','UID','作者','业务链接','来源平台','发布类型','板块','红黑榜类型','是否删除','附件链接','附件SHA256','附件大小'),
 JSON_OBJECT('source_record_id','问题编号','title','问题标题','description','问题描述','source_event_time','发布时间','original_id','原始ID','uid','UID','author','作者','source_url','业务链接','source_platform','来源平台','pub_type','发布类型','section','板块','emotion','红黑榜类型','is_deleted','是否删除','attachment_urls','附件链接','attachment_sha256','附件SHA256','attachment_size','附件大小'));
