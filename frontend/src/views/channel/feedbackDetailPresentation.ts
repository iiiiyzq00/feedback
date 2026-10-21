import type {
  FeedbackAiAnalysis,
  FeedbackAttachmentSummary,
  FeedbackDetail,
} from '@/api/feedback';

export interface FeedbackDetailView {
  sourceChannel: FeedbackDetail['sourceChannel'] | '';
  channelMeta?: FeedbackDetail['channelMeta'];
  reportedAt?: FeedbackDetail['reportedAt'];
  faultTimeResolution?: FeedbackDetail['faultTimeResolution'];
  faultTimeSource?: FeedbackDetail['faultTimeSource'];
  faultTimePrecision?: FeedbackDetail['faultTimePrecision'];
  diagnosticAvailability?: FeedbackDetail['diagnosticAvailability'];
}

const sdkMetaLabels: Record<string, string> = {
  client_feedback_id: '客户端反馈 ID',
  app_id: '应用标识',
  package_name: '应用包名',
  os_version: '系统版本',
  network_type: '网络类型',
  device_model: '设备型号',
  screen_orientation: '屏幕方向',
  input_methods: '输入方式',
  app_session_id: '应用会话 ID',
  trigger_type: '触发方式',
  battery_level: '电量百分比',
  storage_available_mb: '可用存储（MB）',
  integration_mode: '接入模式',
  context_source: '上下文来源',
  app_internal_logs: '应用内部日志',
  crash_stack: '崩溃堆栈',
  account_context: '账号上下文',
  course_id: '课程 ID',
  question_id: '题目 ID',
  order_id: '订单 ID',
  page_route: '页面路由',
  business_id: '业务 ID',
  api_request_summary: '接口请求摘要',
  state_machine: '状态机',
  custom_events: '自定义埋点',
  private_file_manifest: '私有文件清单',
};

const commonMetaLabels: Record<string, string> = {
  original_id_raw: '来源原始 ID',
  headline: '原始标题',
  author: '作者',
  source_platform: '来源平台',
  pub_type_name: '发布类型',
  list_type: '红黑榜类型',
  evaluation_object: '评价对象',
  evaluation_dimension: '评价维度',
  impact_level_raw: '来源影响等级',
  data_period: '数据周期',
  source_department_masked: '来源部门',
  source_role_masked: '来源角色',
  section_raw: '板块',
  folder_name: '话题/文件夹',
  keyword: '关键词',
  emotion_type_raw: '来源情感',
  push_time: '推送时间',
  pub_time: '发布时间',
  create_time: '来源创建时间',
  update_time: '来源更新时间',
  is_deleted_src: '来源删除标记',
  jira_key: 'Jira 编号',
  jira_status_raw: 'Jira 原始状态',
  priority_raw: '原始优先级',
  project_key: '项目 Key',
  project_name: '项目',
  resolution: 'Jira解决结果',
  assignee: 'Jira经办人',
  resolved_at: '解决时间',
  affected_version: '影响版本',
  fix_version: '修复版本',
  module_raw: '来源模块',
  due_date: 'Jira 到期时间',
  comment_summary: '评论摘要',
  source_api_version: '接入版本',
  source_product_name: '产品名称',
  source_app_id: '应用 ID',
  source_app_name: '应用名称',
  source_feedback_type: '反馈类型',
  source_sub_channel: '子渠道',
  source_description: '补充描述',
  attribute_schema_version: '扩展属性版本',
  attribute_mapping_status: '扩展属性状态',
  contact_status: '联系方式状态',
  return_order_no: '商返单号',
  product_name: '产品名称',
  product_model: '产品型号',
  dispatch_date: '派单日期',
  activation_date: '激活日期',
  repair_region: '报修区域',
  province: '省份',
  city: '城市',
  county: '区县',
  district: '行政区',
  application_type_raw: '申请类型',
  exchange_request: '换机需求',
  return_reason: '商返原因',
  fault_confirmed: '故障现象确认',
  fault_remark: '故障备注',
  merchant_return_apply_date: '商返申请日期',
  actual_shipping_date: '实际出货日期',
};

const diagnosticLabels: Record<string, string> = {
  PHONE: '手机号',
  EMAIL: '邮箱',
  DEVICE_SN: '设备序列号',
  USER_ID: '来源用户 ID',
  AUTHENTICATED_USER_ID: '认证用户 ID',
  APPLICATION_LOG: '应用日志',
  ACCOUNT_CONTEXT: '账号上下文',
  PRIVATE_FILE_MANIFEST: '私有文件清单',
};

const expectedDiagnostics: Record<string, string[]> = {
  sdk: [
    'USER_ID',
    'PHONE',
    'DEVICE_SN',
    'APPLICATION_LOG',
    'ACCOUNT_CONTEXT',
    'PRIVATE_FILE_MANIFEST',
  ],
  station_feedback: [
    'USER_ID',
    'AUTHENTICATED_USER_ID',
    'PHONE',
    'DEVICE_SN',
    'APPLICATION_LOG',
  ],
  quality: ['DEVICE_SN', 'USER_ID', 'PHONE', 'APPLICATION_LOG'],
  redblack: ['USER_ID', 'APPLICATION_LOG'],
  jira: ['USER_ID', 'EMAIL', 'DEVICE_SN', 'APPLICATION_LOG'],
};

const omittedMetaKeys = new Set([
  'source_id',
  'created_at',
  'jira_status_raw',
  'processing_stage',
  'ingest_status',
  'retry_count',
  'processed_at',
  'merchant_return_apply_date',
]);

export function displayMetaValue(value: unknown) {
  if (value == null || value === '') return '-';
  if (typeof value === 'string' && /^[{["]/.test(value.trim())) {
    try {
      const parsed = JSON.parse(value);
      return typeof parsed === 'string'
        ? parsed
        : JSON.stringify(parsed, null, 2);
    } catch {
      // 来源扩展字段允许普通文本；解析失败时按原文展示。
    }
  }
  return String(value);
}

export function metaValue(detail: FeedbackDetailView, key: string): unknown {
  return Object.entries(detail.channelMeta || {}).find(
    ([entryKey]) => entryKey === key,
  )?.[1];
}

export function metaEntries(detail: FeedbackDetailView) {
  return Object.entries(detail.channelMeta || {})
    .filter(
      ([key, value]) =>
        value != null && value !== '' && !omittedMetaKeys.has(key),
    )
    .map(([key, value]) => ({
      key,
      label:
        detail.sourceChannel === 'sdk'
          ? sdkMetaLabels[key] || commonMetaLabels[key] || key
          : commonMetaLabels[key] || key,
      value: displayMetaValue(value),
    }));
}

export function primaryTime(detail: FeedbackDetailView) {
  const label =
    detail.sourceChannel === 'redblack'
      ? '发布时间'
      : detail.sourceChannel === 'quality'
        ? '商返申请日期'
        : '故障时间';
  const evidenceLabel =
    detail.sourceChannel === 'quality' ? '日期依据' : '故障时间依据';
  const value =
    detail.sourceChannel === 'redblack'
      ? displayMetaValue(metaValue(detail, 'pub_time'))
      : detail.reportedAt ||
        (detail.sourceChannel === 'quality'
          ? '商返申请日期未提供'
          : '故障时间未提供');
  return { label, evidenceLabel, value };
}

export function faultTimeEvidence(detail: FeedbackDetailView) {
  if (detail.faultTimeResolution === 'NOT_APPLICABLE')
    return '该渠道不产生故障时间';
  if (detail.faultTimeResolution === 'UNPARSEABLE')
    return '来源已提供时间文本，但暂无法可靠解析';
  if (detail.faultTimeResolution !== 'RESOLVED') return '来源未提供';
  const source =
    (
      {
        SDK_CLIENT: 'SDK客户端上报',
        JIRA_DESCRIPTION: 'Jira问题描述',
        STATION_FEEDBACK_DESCRIPTION: '学习机反馈描述',
        STATION_HISTORY_CREATED_PROXY: '历史导入创建时间（代理值）',
        QUALITY_DESCRIPTION: '质量平台故障描述',
        QUALITY_RETURN_APPLY_PROXY: '商返申请日期（代理值）',
      } as Record<string, string>
    )[detail.faultTimeSource ?? ''] || detail.faultTimeSource;
  const precision =
    (
      {
        SECOND: '精确到秒',
        MINUTE: '精确到分',
        HOUR: '精确到小时',
        DAY: '精确到日',
        RANGE: '时间范围',
        APPROXIMATE: '模糊时间',
      } as Record<string, string>
    )[detail.faultTimePrecision ?? ''] || detail.faultTimePrecision;
  return [source, precision].filter(Boolean).join(' · ');
}

export function evidenceCounts(
  detail: FeedbackDetailView,
  attachments: FeedbackAttachmentSummary[],
) {
  const counts = attachments.reduce(
    (result, attachment) => {
      const type = String(attachment.attachmentType || '').toUpperCase();
      if (['IMAGE', 'SCREENSHOT', 'ANNOTATED_SCREENSHOT'].includes(type))
        result.image += 1;
      else if (type === 'LOG') result.log += 1;
      else if (['VIDEO', 'SCREEN_RECORDING'].includes(type)) result.video += 1;
      else result.other += 1;
      return result;
    },
    { image: 0, log: 0, video: 0, other: 0 },
  );
  // 诊断日志只能补足数量提示；正文仍按当前角色实时鉴权，不能混入普通附件。
  if (detail.diagnosticAvailability?.types?.includes('APPLICATION_LOG'))
    counts.log = Math.max(counts.log, 1);
  return counts;
}

export function evidenceSummary(counts: ReturnType<typeof evidenceCounts>) {
  return (
    [
      counts.image && `截图/图片 ${counts.image}`,
      counts.log && `日志 ${counts.log}`,
      counts.video && `视频 ${counts.video}`,
      counts.other && `其他 ${counts.other}`,
    ]
      .filter(Boolean)
      .join(' · ') || '无附件'
  );
}

export function diagnosticStates(detail: FeedbackDetailView) {
  const available = new Set<string>(detail.diagnosticAvailability?.types || []);
  const expected = expectedDiagnostics[detail.sourceChannel || ''] || [];
  return [...new Set([...expected, ...available])].map((type) => ({
    type,
    label: diagnosticLabels[type] || type,
    available: available.has(type),
  }));
}

export function diagnosticSummary(states: ReturnType<typeof diagnosticStates>) {
  const available = states
    .filter((item) => item.available)
    .map((item) => item.label);
  return available.length
    ? `${available.join('、')}可直接查看`
    : `${states.map((item) => item.label).join('、') || '诊断字段'}当前无数据`;
}

const channelLabels: Record<FeedbackDetail['sourceChannel'], string> = {
  sdk: '学习机反馈（SDK 实时上报）',
  station_feedback: '学习机反馈（原反馈平台）',
  jira: '400 Jira',
  quality: '质量平台',
  redblack: '红黑榜',
};

export function channelLabel(
  channel?: FeedbackDetail['sourceChannel'] | '' | null,
) {
  return channel && channel in channelLabels
    ? channelLabels[channel as FeedbackDetail['sourceChannel']]
    : channel || '';
}

export function aiSources(value: FeedbackAiAnalysis['dataSources']) {
  try {
    const sources: unknown = Array.isArray(value)
      ? value
      : JSON.parse(value || '[]');
    return Array.isArray(sources) ? sources.map(String).join('、') || '-' : '-';
  } catch {
    return '-';
  }
}

export const percent = (value: unknown) =>
  value == null ? '-' : `${Math.round(Number(value) * 100)}%`;
