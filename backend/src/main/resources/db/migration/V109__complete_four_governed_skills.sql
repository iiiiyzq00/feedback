UPDATE t_ai_task SET
  input_schema=JSON_OBJECT('type','object','required',JSON_ARRAY('instanceId','ruleCode','severity','metric','currentValue','threshold')),
  output_schema=JSON_OBJECT('type','object','required',JSON_ARRAY('summary','suggested_actions'))
WHERE record_type='SKILL_DEF' AND skill_code='alert_route';

UPDATE t_ai_task SET
  input_schema=JSON_OBJECT('type','object','required',JSON_ARRAY('incident','alerts','condition','impact','dimensions')),
  output_schema=JSON_OBJECT('type','object','required',JSON_ARRAY('rootCause','solution','confidence','validation'))
WHERE record_type='SKILL_DEF' AND skill_code='root_cause_quick';

UPDATE t_ai_task SET
  input_schema=JSON_OBJECT('type','object','required',JSON_ARRAY('issueType','module','publishedOnly')),
  output_schema=JSON_OBJECT('type','object','required',JSON_ARRAY('matches'))
WHERE record_type='SKILL_DEF' AND skill_code='knowledge_search';

UPDATE t_ai_task SET
  input_schema=JSON_OBJECT('type','object','required',JSON_ARRAY('question')),
  output_schema=JSON_OBJECT('type','object','required',JSON_ARRAY('intent','days','channels'))
WHERE record_type='SKILL_DEF' AND skill_code='assistant_intent_parse';
