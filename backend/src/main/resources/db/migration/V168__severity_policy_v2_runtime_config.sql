-- severity-policy-v2 运行时配置（2026-08-06 激活，用户视角）
-- severity-policy-v1 保留为历史版本（is_active=0，可回滚）
-- 注意：config_value 需与 backend/src/main/kotlin/com/feedback/service/ai/SeverityPolicyService.kt
--       中的 V1_DEF / V2_DEF 保持同步；代码内嵌定义为无配置环境回退基线。

INSERT INTO t_system_config
  (config_key, config_type, config_version, config_value, config_schema_version,
   lifecycle_status, approval_status, owner_team, scope_key, config_subtype,
   subject_user_id, secondary_user_id, record_status, record_version,
   effective_from, effective_to, config_desc, change_set_id, change_reason,
   source_config_id, is_active, created_by)
VALUES
  ('SEVERITY_POLICY:v1', 'SEVERITY_POLICY', 'severity-policy-v1',
   JSON_OBJECT(
     'policyVersion', 'severity-policy-v1',
     'basedOn', NULL,
     'evidenceCode', 'SEVERITY_POLICY_V1',
     'rubric', '严重度必须遵循 severity-policy-v1，并按影响而不是情绪或单个关键词判断；多个等级同时满足时取最高等级：\n- S0 致命：大范围核心服务不可用、不可恢复的数据丢失，或明确的人身安全/账号与隐私泄露风险。\n- S1 严重：核心功能对受影响用户不可用，持续崩溃/卡死/无法启动，或硬件故障需要维修换机；影响尚未达到 S0。\n- S2 中等：重要功能错误或明显降级，例如卡顿、操作失败、结果/内容错误；通常存在替代路径且不满足 S0/S1。\n- S3 轻微：非阻断体验问题、一般咨询、需求建议或投诉，但没有证据证明核心功能不可用。\n- S4 无故障影响：纯表扬或正向反馈，且不包含任何故障、风险或改进诉求。\n判断时必须结合影响范围、核心功能、可恢复性、替代路径、安全风险和复现情况。证据不足不得上调；不得把负面情绪直接当作高严重度，也不得把含故障的正向表述判为 S4。\nexplanation 必须说明命中的影响条件；evidence_codes 至少包含 SEVERITY_POLICY_V1 和对应等级代码 SEVERITY_S0 至 SEVERITY_S4。',
     'levels', JSON_ARRAY(
       JSON_OBJECT('code','S0','label','致命','definition','大范围核心不可用、不可恢复数据损失或安全隐私风险'),
       JSON_OBJECT('code','S1','label','严重','definition','核心功能不可用、频繁崩溃或需立即处置的硬件故障'),
       JSON_OBJECT('code','S2','label','中等','definition','重要功能错误或明显降级，但存在替代路径'),
       JSON_OBJECT('code','S3','label','轻微','definition','非阻断体验问题、一般咨询或需求建议'),
       JSON_OBJECT('code','S4','label','无故障影响','definition','纯正向反馈且没有故障或风险')
     ),
     'rules', JSON_OBJECT(
       'safetyOrSecurity', JSON_ARRAY('起火','冒烟','触电','账号泄露','隐私泄露','数据泄露','远程控制'),
       'dataLoss', JSON_ARRAY('数据丢失','数据清空','记录丢失'),
       'irreversible', JSON_ARRAY('无法恢复','不可恢复','永久丢失','全部丢失','数据清空'),
       'broadImpact', JSON_ARRAY('大面积','全量','全部用户','所有用户','普遍','全校','全班'),
       'coreUnavailable', JSON_ARRAY('无法登录','无法开机','无法启动','无法使用','服务不可用','全部无法'),
       'severeFailure', JSON_ARRAY('闪退','崩溃','卡死','无响应','无法使用','无法开机','无法启动'),
       'repairRequired', JSON_ARRAY('换机','退机','返修','维修'),
       'degradedFailure', JSON_ARRAY('卡顿','失败','错误','结果不对','答案错','题目错','内容错','解析错')
     )
   ),
   'severity-policy-v1', 'RETIRED', 'APPROVED', 'AI', 'GLOBAL', 'AI_SEVERITY',
   NULL, NULL, 'RETIRED', 1, NULL, NULL,
   'severity-policy-v1 历史基线（2026-08-06 起为历史版本，保留可回滚）',
   NULL, NULL, NULL, 0, 0),

  ('SEVERITY_POLICY:v2', 'SEVERITY_POLICY', 'severity-policy-v2',
   JSON_OBJECT(
     'policyVersion', 'severity-policy-v2',
     'basedOn', 'severity-policy-v1',
     'evidenceCode', 'SEVERITY_POLICY_V2',
     'rubric', '严重度必须遵循 severity-policy-v2，回答"这条反馈对用户今天的学习/使用有多大影响"，按影响而不是情绪或单个关键词判断；多个等级同时满足时取最高等级：\n- S0 致命：人身安全（漏电/冒烟/起火/过热/触电）、账号或隐私泄露、不可恢复的数据丢失，或大范围（全班/全校/所有用户）核心能力不可用。\n- S1 严重：学习被完全阻断（无法开机/卡 logo/进不了系统/上课闪退/自动关机/无法登录进课程），或硬件需送修换机（碎屏/无法充电/黑屏无显示/触屏完全失效/花屏无法显示）；影响尚未达到 S0。\n- S2 中等：明显降级但有替代路径，例如断网可开热点、白屏重试可恢复、内容错误、数据不同步、卡顿明显影响体验、音频或按键可用替代方式；外观类硬件问题（外壳变形、显示偏暗/发红/漏光、杂音）按可继续使用判 S2。\n- S3 轻微：非阻断体验问题（加载慢/文案不清/按钮位置）、一般咨询、需求建议，或无故障证据的投诉。\n- S4 无影响：纯表扬或正向反馈，不包含任何故障、风险或改进诉求。\n判断时必须结合影响范围、学习阻断程度、可恢复性、替代路径、安全风险和复现情况。证据不足不得上调；不得把负面情绪直接当作高严重度，也不得把含故障的正向表述判为 S4。\nexplanation 必须说明命中的影响条件；evidence_codes 至少包含 SEVERITY_POLICY_V2 和对应等级代码 SEVERITY_S0 至 SEVERITY_S4。',
     'levels', JSON_ARRAY(
       JSON_OBJECT('code','S0','label','致命','definition','人身安全（漏电/冒烟/起火/过热/触电）、账号或隐私泄露、不可恢复的数据丢失，或大范围核心能力不可用'),
       JSON_OBJECT('code','S1','label','严重','definition','学习被完全阻断（无法开机/卡logo/进不了系统/上课闪退/自动关机/无法登录），或硬件需送修换机'),
       JSON_OBJECT('code','S2','label','中等','definition','明显降级但有替代路径（断网可热点/白屏可重试/内容错误/数据不同步/卡顿明显影响体验）'),
       JSON_OBJECT('code','S3','label','轻微','definition','非阻断体验问题、一般咨询、需求建议或无故障证据的投诉'),
       JSON_OBJECT('code','S4','label','无故障影响','definition','纯表扬或正向反馈，不包含故障、风险或改进诉求')
     ),
     'rules', JSON_OBJECT(
       'safetyOrSecurity', JSON_ARRAY('漏电','冒烟','起火','过热','触电','账号泄露','隐私泄露','数据泄露','远程控制'),
       'dataLoss', JSON_ARRAY('数据丢失','数据清空','记录丢失','学习记录丢失'),
       'irreversible', JSON_ARRAY('无法恢复','不可恢复','永久丢失','全部丢失','数据清空'),
       'broadImpact', JSON_ARRAY('大面积','全量','全部用户','所有用户','普遍','全校','全班'),
       'coreUnavailable', JSON_ARRAY('无法登录','登录不上','无法开机','无法启动','无法使用','服务不可用','全部无法','卡logo','卡在logo','进不了系统','无法进入'),
       'severeFailure', JSON_ARRAY('闪退','崩溃','卡死','无响应','无法使用','无法开机','无法启动','自动关机','黑屏','上课闪退'),
       'repairRequired', JSON_ARRAY('换机','退机','返修','维修','送修','返厂','碎屏','屏裂','花屏','触屏','无法充电','黑屏无显示'),
       'degradedFailure', JSON_ARRAY('卡顿','失败','错误','结果不对','答案错','题目错','内容错','解析错','排版','不更新','不同步','断网','白屏','杂音','偏暗','变形','漏光','发红')
     )
   ),
   'severity-policy-v2', 'PUBLISHED', 'APPROVED', 'AI', 'GLOBAL', 'AI_SEVERITY',
   NULL, NULL, 'PUBLISHED', 2, NULL, NULL,
   'severity-policy-v2 用户视角严重度（2026-08-06 激活）',
   NULL, NULL, NULL, 1, 0);
