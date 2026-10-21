import type { FeedbackListRow } from '@/api/feedback';
import type { ColumnDefinition } from './channelColumns';

const issueCategoryLabels: Record<string, string> = {
  BUG: '故障',
  HARDWARE: '硬件问题',
  CONTENT_ERROR: '内容错误',
  DEMAND: '功能需求',
  CONSULT: '咨询',
  PRAISE: '表扬',
  COMPLAINT: '投诉',
  UNKNOWN: '待确认',
};
const diagnosticTypeLabels: Record<string, string> = {
  PHONE: '手机号',
  EMAIL: '邮箱',
  DEVICE_SN: '设备序列号',
  USER_ID: '用户 ID',
  AUTHENTICATED_USER_ID: '认证用户 ID',
  APPLICATION_LOG: '应用日志',
  ACCOUNT_CONTEXT: '账号上下文',
  PRIVATE_FILE_MANIFEST: '私有文件清单',
};
const textColumns = new Set(['title', 'description', 'resolution', 'keyword']);
const timeColumns = new Set([
  'reportedAt',
  'publishedAt',
  'createdAt',
  'updatedAt',
  'sourceUpdatedAt',
  'platformUpdatedAt',
  'ingestTime',
]);

export function issueCategoryName(value?: string) {
  return value ? (issueCategoryLabels[value] ?? value) : '-';
}

export function diagnosticTypeLabel(type: string) {
  return diagnosticTypeLabels[type] ?? type;
}

export function isTextColumn(prop: string) {
  return textColumns.has(prop);
}

export function isTimeColumn(prop: string) {
  return timeColumns.has(prop);
}

function parseDate(value: unknown) {
  if (value instanceof Date)
    return Number.isNaN(value.getTime()) ? null : new Date(value.getTime());
  if (typeof value !== 'string' && typeof value !== 'number') return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

const pad = (value: number) => String(value).padStart(2, '0');

export function formatInlineTime(value: unknown) {
  const date = parseDate(value);
  if (!date) return '-';
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export function formatColumnValue(
  column: ColumnDefinition,
  value: unknown,
) {
  if (value == null || value === '') return '-';
  if (column.prop === 'publishedAt') return formatInlineTime(value);
  if (column.prop === 'listType') {
    return (
      (
        { RED: '红榜', BLACK: '黑榜', UNKNOWN: '未识别' } as Record<
          string,
          string
        >
      )[String(value)] ?? String(value)
    );
  }
  if (column.prop === 'exchangeRequest')
    return value === true || value === 1 || value === '1' ? '是' : '否';
  return String(value);
}

function ownValue(value: object, prop: PropertyKey): unknown {
  return Object.prototype.hasOwnProperty.call(value, prop)
    ? Reflect.get(value, prop)
    : undefined;
}

export function rowValue(
  row: FeedbackListRow,
  prop: ColumnDefinition['prop'],
) {
  return ownValue(row, prop);
}

export function channelMetaValue(
  row: FeedbackListRow,
  prop: NonNullable<ColumnDefinition['meta']>,
) {
  return row.channelMeta ? ownValue(row.channelMeta, prop) : undefined;
}
