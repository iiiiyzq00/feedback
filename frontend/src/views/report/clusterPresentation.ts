import type { ClusterFeedbackItem } from '@/api/report';

const clusterStatusLabels: Record<string, string> = {
  ACTIVE: '处理中',
  MITIGATING: '缓解中',
  REOPENED: '重新打开',
  RESOLVED: '已解决',
  MERGED: '已合并',
  SPLIT_ARCHIVED: '拆分归档',
  ARCHIVED: '已归档',
};
const controlledInfoLabels: Record<string, string> = {
  PHONE: '手机号',
  EMAIL: '邮箱',
  DEVICE_SN: '设备序列号',
  USER_ID: '用户 ID',
  AUTHENTICATED_USER_ID: '认证用户 ID',
  APPLICATION_LOG: '应用日志',
  ACCOUNT_CONTEXT: '账号上下文',
  PRIVATE_FILE_MANIFEST: '私有文件清单',
};

export function clusterStatusLabel(status: string) {
  return clusterStatusLabels[status] || '待确认';
}

export function formatDateTime(value: unknown) {
  if (!value) return '时间未提供';
  const date = new Date(String(value));
  return Number.isNaN(date.getTime())
    ? '时间未提供'
    : date.toLocaleString('zh-CN');
}

export function confidenceText(value: unknown) {
  const number = Number(value);
  return Number.isFinite(number) ? `${Math.round(number * 100)}%` : '-';
}

export function metric(value: unknown) {
  return value == null ? '未知' : String(value);
}

export function channelLabel(value: unknown) {
  return (
    (
      {
        station_feedback: '学习机',
        jira: 'Jira',
        quality: '质量平台',
        redblack: '红黑榜',
      } as Record<string, string>
    )[String(value || '').toLowerCase()] || '其他来源'
  );
}

export function decisionLabel(row: ClusterFeedbackItem) {
  if (row.aiAction === 'LINK_EXISTING') return 'AI 自动关联';
  if (row.aiAction === 'CREATE_NEW') return 'AI 自动新建';
  if (String(row.decisionReason || '').includes('AI自动归簇低置信'))
    return '低置信自动新建';
  if (row.decisionType === 'NEW_CLUSTER') return '规则自动新建';
  if (row.decisionType === 'AUTO_HIGH') return '规则自动关联';
  return row.decisionType || row.aiAction || '未提供';
}

export function controlledInfoTypes(row: ClusterFeedbackItem) {
  return [
    ...new Set(
      String(row.controlledInfoTypes || '')
        .split(',')
        .filter(Boolean),
    ),
  ];
}

export function controlledInfoLabel(type: string) {
  return controlledInfoLabels[type] || type;
}

export function feedbackTimeLabel(channel: unknown) {
  return String(channel || '').toLowerCase() === 'redblack'
    ? '发布时间'
    : '故障时间';
}
