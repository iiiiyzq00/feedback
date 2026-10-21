-- P1 基础必达使用已治理的公共渠道；私信、任务和短信仅在规则显式配置时启用。
UPDATE t_alert_notification_policy
SET required_channels=JSON_ARRAY('FEISHU_CARD','EMAIL','IN_APP'),
    allowed_channels=JSON_ARRAY('FEISHU_CARD','FEISHU_DM','FEISHU_TASK','SMS','EMAIL','IN_APP'),
    policy_version='v2'
WHERE severity='P1';
