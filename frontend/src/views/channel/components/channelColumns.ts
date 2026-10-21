import type {
  JiraFeedbackChannelMeta,
  FeedbackListRow,
  QualityFeedbackChannelMeta,
  RedblackFeedbackChannelMeta,
  SdkFeedbackChannelMeta,
  StationFeedbackChannelMeta,
} from '@/api/feedback';

export type ChannelView =
  | 'all'
  | 'learning_machine'
  | 'station_feedback'
  | 'jira'
  | 'quality'
  | 'redblack'
  | 'sdk';

export interface ColumnDefinition {
  readonly prop: ColumnKey;
  readonly label: string;
  readonly width?: number;
  readonly minWidth?: number;
  readonly sortable?: boolean;
  readonly meta?: ChannelMetaKey;
}

type ChannelMetaKey =
  | keyof SdkFeedbackChannelMeta
  | keyof JiraFeedbackChannelMeta
  | keyof QualityFeedbackChannelMeta
  | keyof RedblackFeedbackChannelMeta
  | keyof StationFeedbackChannelMeta;

type ColumnKey =
  | keyof FeedbackListRow
  | 'appName'
  | 'applicationType'
  | 'assignee'
  | 'author'
  | 'deviceModel'
  | 'diagnostics'
  | 'dispatchDate'
  | 'evidence'
  | 'exchangeRequest'
  | 'issueKey'
  | 'keyword'
  | 'learningMachineSource'
  | 'listType'
  | 'networkType'
  | 'priority'
  | 'productModel'
  | 'productName'
  | 'repairRegion'
  | 'resolution'
  | 'returnOrderNo'
  | 'sourcePlatform';

/**
 * 反馈列表字段是经产品确认的页面合同，不能再被运行时草稿隐藏、改名或重排。
 * 这里保留 SDK/Station 的内部视图定义，仅用于兼容既有组件调用；产品入口仍为
 * learning_machine，底层来源码不会因此合并。
 */
export const CHANNEL_COLUMNS = {
  sdk: [
    { prop: 'reportedAt', label: '故障时间', width: 170, sortable: true },
    { prop: 'title', label: '问题摘要', minWidth: 240 },
    { prop: 'ownerName', label: '负责人', width: 110 },
    { prop: 'module', label: '功能模块', width: 110 },
    { prop: 'appVersion', label: '应用版本', width: 110 },
    {
      prop: 'deviceModel',
      meta: 'device_model',
      label: '设备型号',
      width: 130,
    },
    { prop: 'networkType', meta: 'network_type', label: '网络', width: 80 },
    { prop: 'problemType', label: '问题类型', width: 110 },
    { prop: 'evidence', label: '分析证据', width: 190 },
  ],
  station_feedback: [
    { prop: 'reportedAt', label: '故障发生时间', width: 170, sortable: true },
    { prop: 'title', label: '问题内容', minWidth: 360 },
    { prop: 'appName', meta: 'source_app_name', label: '应用名称', width: 150 },
    {
      prop: 'sourcePlatform',
      meta: 'source_platform',
      label: '来源平台',
      width: 110,
    },
    { prop: 'diagnostics', label: '关键信息', width: 300 },
    { prop: 'evidence', label: '分析证据', width: 270 },
  ],
  learning_machine: [
    { prop: 'reportedAt', label: '故障发生时间', width: 170, sortable: true },
    { prop: 'title', label: '问题内容', minWidth: 360 },
    { prop: 'learningMachineAppName', label: '应用名称', width: 170 },
    { prop: 'learningMachineSource', label: '来源平台', width: 130 },
    { prop: 'diagnostics', label: '关键信息', width: 300 },
    { prop: 'evidence', label: '分析证据', width: 270 },
  ],
  jira: [
    { prop: 'reportedAt', label: '故障发生时间', width: 170, sortable: true },
    { prop: 'title', label: '问题内容', minWidth: 360 },
    { prop: 'issueKey', meta: 'jira_key', label: 'Jira 编号', width: 120 },
    { prop: 'assignee', meta: 'assignee', label: 'Jira经办人', width: 110 },
    { prop: 'priority', meta: 'priority_raw', label: '优先级', width: 90 },
    {
      prop: 'resolution',
      meta: 'resolution',
      label: 'Jira解决结果',
      width: 110,
    },
    {
      prop: 'sourceUpdatedAt',
      label: 'Jira 更新时间',
      width: 170,
      sortable: true,
    },
    { prop: 'diagnostics', label: '关键信息', width: 300 },
    { prop: 'evidence', label: '分析证据', width: 270 },
  ],
  quality: [
    { prop: 'reportedAt', label: '商返申请日期', width: 170, sortable: true },
    { prop: 'diagnostics', label: '关键信息', width: 300 },
    {
      prop: 'returnOrderNo',
      meta: 'return_order_no',
      label: '商返单号',
      width: 150,
    },
    { prop: 'title', label: '故障现象', minWidth: 230 },
    {
      prop: 'productName',
      meta: 'product_name',
      label: '产品名称',
      width: 150,
    },
    {
      prop: 'productModel',
      meta: 'product_model',
      label: '产品型号',
      width: 130,
    },
    { prop: 'province', label: '省份', width: 80 },
    { prop: 'city', label: '城市', width: 80 },
    {
      prop: 'repairRegion',
      meta: 'repair_region',
      label: '报修区域',
      width: 110,
    },
    {
      prop: 'applicationType',
      meta: 'application_type_raw',
      label: '申请类型',
      width: 100,
    },
    {
      prop: 'dispatchDate',
      meta: 'dispatch_date',
      label: '派单日期',
      width: 120,
    },
    {
      prop: 'exchangeRequest',
      meta: 'exchange_request',
      label: '换机需求',
      width: 90,
    },
    { prop: 'evidence', label: '分析证据', width: 270 },
  ],
  redblack: [
    { prop: 'publishedAt', meta: 'pub_time', label: '发布时间', width: 170 },
    { prop: 'diagnostics', label: '关键信息', width: 300 },
    { prop: 'listType', meta: 'list_type', label: '红黑榜类型', width: 110 },
    {
      prop: 'sourcePlatform',
      meta: 'source_platform',
      label: '来源平台',
      width: 100,
    },
    { prop: 'author', meta: 'author', label: '作者', width: 120 },
    { prop: 'title', label: '问题内容', minWidth: 360 },
    { prop: 'emotion', label: '标准情感', width: 110 },
    { prop: 'keyword', meta: 'keyword', label: '关键词', width: 130 },
    { prop: 'evidence', label: '分析证据', width: 270 },
  ],
  all: [
    { prop: 'reportedAt', label: '反馈时间', width: 170, sortable: true },
    { prop: 'title', label: '问题内容', minWidth: 420 },
    { prop: 'diagnostics', label: '关键信息', width: 300 },
    { prop: 'evidence', label: '分析证据', width: 270 },
  ],
} as const satisfies Record<
  ChannelView,
  readonly ColumnDefinition[]
>;

export function feedbackColumnsFor(
  view: string | undefined,
): readonly ColumnDefinition[] {
  if (
    view &&
    Object.prototype.hasOwnProperty.call(CHANNEL_COLUMNS, view)
  ) {
    return CHANNEL_COLUMNS[view as ChannelView];
  }
  return CHANNEL_COLUMNS.all;
}

export function learningMachineSourceLabel(sourceChannel: unknown): string {
  switch (String(sourceChannel ?? '').toUpperCase()) {
    case 'SDK':
      return 'SDK';
    case 'STATION_FEEDBACK':
      return '旧反馈平台';
    default:
      return '未知来源';
  }
}
