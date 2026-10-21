UPDATE t_import_template
SET is_active = 0, updated_at = CURRENT_TIMESTAMP(3)
WHERE source_channel = 'JIRA' AND is_active = 1;

INSERT INTO t_import_template(
  template_name, source_channel, version, schema_version, header_columns, field_mappings, is_active
) VALUES (
  'Jira完整字段导入/API统一映射', 'JIRA', 'v1.3.0', 'import-contract-v1',
  JSON_ARRAY(
    '主题','问题关键字','问题ID','问题类型','状态','项目关键字','项目名称','项目类型','项目主管','项目描述','项目URL',
    '优先级','解决结果','经办人','报告人','创建人','创建','更新','最近查看的','解决','影响版本','解决版本','模块',
    '到期日','投票','标签','标签#2','标签#3','描述','环境','关注人','记录工作时间','原预估时间','剩余时间','耗费时间',
    '工作比率','Σ 原预估时间','Σ 预估剩余时间','Σ 耗费时间','安全级别','事务的内向链接 (Duplicate)','向外链接的问题(Duplicate)'
  ),
  JSON_OBJECT(
    'title','主题','source_record_id','问题关键字','jira_issue_id','问题ID','jira_issue_type','问题类型','status','状态',
    'project_key','项目关键字','project_name','项目名称','project_type','项目类型','project_lead','项目主管',
    'project_description','项目描述','project_url','项目URL','priority','优先级','resolution','解决结果','assignee','经办人',
    'reporter','报告人','creator','创建人','source_event_time','创建','source_update_time','更新','last_viewed_at','最近查看的',
    'resolved_at','解决','affected_version','影响版本','fix_version','解决版本','function_module','模块','due_date','到期日',
    'votes','投票','label_1','标签','label_2','标签#2','label_3','标签#3','description','描述','environment','环境',
    'watchers','关注人','worklog_time','记录工作时间','original_estimate_raw','原预估时间',
    'remaining_estimate_raw','剩余时间','time_spent_raw','耗费时间','work_ratio','工作比率',
    'sigma_original_estimate','Σ 原预估时间','sigma_remaining_estimate','Σ 预估剩余时间',
    'sigma_time_spent','Σ 耗费时间','security_level','安全级别',
    'inward_duplicate_link','事务的内向链接 (Duplicate)','outward_duplicate_link','向外链接的问题(Duplicate)'
  ),
  1
);
