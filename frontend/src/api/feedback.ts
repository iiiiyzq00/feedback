import { download, get, post } from './request';
import type { AxiosProgressEvent, AxiosRequestConfig } from 'axios';

export type PhysicalChannelCode =
  | 'SDK'
  | 'STATION_FEEDBACK'
  | 'JIRA'
  | 'QUALITY'
  | 'REDBLACK';
export type FeedbackSourceChannel =
  | 'sdk'
  | 'station_feedback'
  | 'jira'
  | 'quality'
  | 'redblack';

declare const feedbackChannelCsvBrand: unique symbol;
export type FeedbackChannelCsv = string & {
  readonly [feedbackChannelCsvBrand]: true;
};

/** 仅由封闭五渠道码组装后端既有逗号分隔线协议，调用方不能传入任意字符串。 */
export function toFeedbackChannelCsv(
  channels: readonly PhysicalChannelCode[],
): FeedbackChannelCsv {
  return channels.join(',') as FeedbackChannelCsv;
}

/** 反馈反馈列表公开查询；当前页面只使用关键词、时间、分页、排序和固定渠道。 */
export interface FeedbackListRequest {
  channel?: PhysicalChannelCode | FeedbackSourceChannel;
  /** 五渠道码的逗号分隔线协议值，只能由反馈固定入口组装。 */
  channels?: FeedbackChannelCsv;
  keyword?: string;
  startTime?: string;
  endTime?: string;
  page?: number;
  size?: number;
  sortBy?:
    | 'reportedAt'
    | 'sourceUpdatedAt'
    | 'platformUpdatedAt'
    | 'severity'
    | 'status'
    | 'title';
  sortDirection?: 'asc' | 'desc';
}

/** 兼容现有页面组件名称；线上的请求字段由 FeedbackListRequest 封闭定义。 */
export type FeedbackQuery = FeedbackListRequest;

export interface FeedbackListRow {
  id: number;
  sourceChannel: FeedbackSourceChannel;
  sourceRecordId?: string;
  title?: string;
  description?: string | null;
  module?: string;
  problemType?: string | null;
  issueCategory?: string;
  severity?: string | null;
  severityProvisional?: boolean;
  severityReviewStatus?: string | null;
  emotion?: string | null;
  status: string;
  distributionStatus?: string;
  reportedAt: string | null;
  sourceUpdatedAt?: string | null;
  platformUpdatedAt?: string | null;
  ingestTime?: string | null;
  appVersion?: string | null;
  province?: string | null;
  city?: string | null;
  ownerId?: number | null;
  ownerName?: string | null;
  attachmentCount?: number;
  screenshotCount?: number;
  logCount?: number;
  videoCount?: number;
  channelMeta?:
    | SdkFeedbackChannelMeta
    | JiraFeedbackChannelMeta
    | QualityFeedbackChannelMeta
    | RedblackFeedbackChannelMeta
    | StationFeedbackChannelMeta
    | null;
  diagnosticAvailability?: FeedbackDiagnosticAvailability;
  /** 当前页已按角色呈现的非日志关键信息；不得写入浏览器持久化存储。 */
  keyDiagnostics?: FeedbackDiagnosticItem[];
  diagnosticsVisibility?: FeedbackContentVisibility;
  learningMachineAppName?: string | null;
  publishedAt?: string | null;
  sdkDataType?: 'REAL_SDK' | 'SIMULATED_IMPORT' | null;
  sentimentConflict?: boolean;
}

export interface FeedbackListStats {
  total: number;
  latestTime?: string | null;
  latestUpdateTime?: string | null;
  latestIngestTime?: string | null;
  channelCount: number;
  unassignedCount: number;
  activeCount: number;
  resolvedCount: number;
  withEvidenceCount: number;
  positiveCount: number;
  negativeCount: number;
  newRecords5m: number;
}

export interface FeedbackListResponse {
  records: FeedbackListRow[];
  total: number;
  page: number;
  size: number;
  stats: FeedbackListStats;
}

export function getFeedbackList(
  params: FeedbackListRequest,
): Promise<FeedbackListResponse> {
  return get('/feedback', { params });
}

export interface FeedbackAiAnalysis {
  summary?: string | null;
  application?: string | null;
  functionModule?: string | null;
  category?: string | null;
  sentiment?: string | null;
  riskType?: string | null;
  recommendedTeam?: string | null;
  dataSources?: string | null;
  severity?: string | null;
  confidence?: number | null;
  reasoning?: string | null;
  modelVersion?: string | null;
  processedAt?: string | null;
  promptVersion?: string | null;
}

/** 查询时刻的诊断存在性快照；不含敏感值，也不能替代打开日志时的实时附件授权。 */
export interface FeedbackDiagnosticAvailability {
  /** 至少存在一种可查看诊断或日志元数据。 */
  available?: boolean;
  /** 稳定去重的类型集合；未知 SDK 类型按服务端大写值保留。 */
  types?: string[];
  /** 反馈固定为 false，直接查看不形成用途审批。 */
  requiresPurpose?: boolean;
}

/** 字段转换历史；值已经过服务端字段策略过滤，时间按 Asia/Shanghai 解释。 */
export interface FeedbackFieldHistory {
  id: number;
  fieldName?: string | null;
  oldValue?: string | null;
  newValue?: string | null;
  changeType?: string | null;
  changedByType?: string | null;
  changedByUserId?: number | null;
  mappingVersion?: string | null;
  normalizationVersion?: string | null;
  changeReason?: string | null;
  createdAt?: string | null;
  operator?: string | null;
}

export interface FeedbackNavigation {
  previousId?: number | null;
  nextId?: number | null;
}

export interface FeedbackClusterSummary {
  clusterId?: number;
  clusterName?: string | null;
  total?: number | null;
  similarity?: number | null;
  decisionReason?: string | null;
  decisionVersion?: string | null;
}

/** 五类反馈渠道字段保持后端历史 snake_case JSON 契约，不接受未声明字段。 */
export interface SdkFeedbackChannelMeta {
  client_feedback_id?: string | null;
  app_id?: string | null;
  package_name?: string | null;
  os_version?: string | null;
  network_type?: string | null;
  device_model?: string | null;
  screen_orientation?: string | null;
  input_methods?: string | null;
  trigger_type?: string | null;
  battery_level?: number | null;
  storage_available_mb?: number | null;
  integration_mode?: string | null;
  context_source?: string | null;
  page_route?: string | null;
  business_id?: string | null;
  crash_stack?: string | null;
  course_id?: string | null;
  question_id?: string | null;
  order_id?: string | null;
  api_request_summary?: string | null;
  state_machine?: string | null;
  custom_events?: string | null;
}

export interface JiraFeedbackChannelMeta {
  jira_key?: string | null;
  priority_raw?: string | null;
  jira_status_raw?: string | null;
  project_key?: string | null;
  project_name?: string | null;
  resolution?: string | null;
  assignee?: string | null;
  jira_created_at?: string | null;
  jira_updated_at?: string | null;
  resolved_at?: string | null;
  affected_version?: string | null;
  fix_version?: string | null;
  module_raw?: string | null;
  due_date?: string | null;
  label_1?: string | null;
  label_2?: string | null;
  label_3?: string | null;
  comment_summary?: string | null;
}

export interface QualityFeedbackChannelMeta {
  return_order_no?: string | null;
  product_name?: string | null;
  product_model?: string | null;
  dispatch_date?: string | null;
  activation_date?: string | null;
  repair_region?: string | null;
  province?: string | null;
  city?: string | null;
  county?: string | null;
  district?: string | null;
  application_type_raw?: string | null;
  exchange_request?: boolean | null;
  return_reason?: string | null;
  fault_confirmed?: string | null;
  fault_remark?: string | null;
  merchant_return_apply_date?: string | null;
  actual_shipping_date?: string | null;
}

export interface RedblackFeedbackChannelMeta {
  original_id_raw?: string | null;
  headline?: string | null;
  author?: string | null;
  source_platform?: string | null;
  pub_type_name?: string | null;
  list_type?: string | null;
  section_raw?: string | null;
  folder_name?: string | null;
  keyword?: string | null;
  emotion_type_raw?: string | null;
  evaluation_object?: string | null;
  evaluation_dimension?: string | null;
  impact_level_raw?: string | null;
  data_period?: string | null;
  source_department_masked?: string | null;
  source_role_masked?: string | null;
  push_time?: string | null;
  pub_time?: string | null;
  create_time?: string | null;
  update_time?: string | null;
  is_deleted_src?: boolean | null;
}

export interface StationFeedbackChannelMeta {
  source_api_version?: string | null;
  product_id?: string | null;
  source_product_name?: string | null;
  source_app_id?: string | null;
  source_app_name?: string | null;
  source_platform?: string | null;
  source_feedback_type?: string | null;
  source_sub_channel?: string | null;
  source_description?: string | null;
  attribute_schema_version?: string | null;
  attribute_mapping_status?: string | null;
  contact_status?: string | null;
  ingest_status?: string | null;
  processing_stage?: string | null;
  retry_count?: number | null;
  processed_at?: string | null;
  created_at?: string | null;
}

/**
 * 字段策略可省略除 id、sourceChannel、status、reportedAt 外的顶层字段；
 * reportedAt 虽始终存在，但来源无可靠时间时为 null。
 */
/**
 * 详情顶层合同。字段策略可能省略除结构字段外的属性；嵌套对象沿用同一请求开始时固定的策略快照，
 * 不应把属性缺失解释为数据库中一定没有值。
 */
interface FeedbackDetailBase {
  id: number;
  sourceRecordId?: string;
  appVersion?: string | null;
  reportedAt: string | null;
  faultTimePrecision?: string | null;
  faultTimeSource?: string | null;
  faultTimeResolution?: string | null;
  ingestTime?: string | null;
  sourceUpdatedAt?: string | null;
  importBatchId?: number | null;
  title?: string;
  description?: string | null;
  module?: string;
  usageStage?: string | null;
  problemType?: string | null;
  issueCategory?: string | null;
  severity?: string | null;
  canonicalSeverity?: string | null;
  severityProvisional?: boolean;
  severityReviewStatus?: string | null;
  emotion?: string | null;
  reproducibility?: string | null;
  status: string;
  distributionStatus?: string;
  maskingStatus?: string;
  platformUpdatedAt?: string | null;
  statusMappingVersion?: string | null;
  statusMappingResult?: string | null;
  statusMappedAt?: string | null;
  mappingRule?: string | null;
  normalizationVersion?: string | null;
  qualityScore?: number | null;
  qualityRuleVersion?: string | null;
  importBatchCode?: string | null;
  importBatchStatus?: string | null;
  ownerId?: number | null;
  ownerName?: string | null;
  assignmentVersion?: number | null;
  assignedAt?: string | null;
  region?: string;
  deviceInfo?: string | null;
  externalLinkAvailable?: boolean;
  diagnosticAvailability?: FeedbackDiagnosticAvailability;
  fieldHistory?: FeedbackFieldHistory[];
  attachments?: FeedbackAttachmentSummary[];
  navigation?: FeedbackNavigation;
  clusterSummary?: FeedbackClusterSummary | null;
  aiResult?: FeedbackAiAnalysis | null;
}

export type FeedbackDetail = FeedbackDetailBase &
  (
    | { sourceChannel: 'sdk'; channelMeta?: SdkFeedbackChannelMeta | null }
    | { sourceChannel: 'jira'; channelMeta?: JiraFeedbackChannelMeta | null }
    | {
        sourceChannel: 'quality';
        channelMeta?: QualityFeedbackChannelMeta | null;
      }
    | {
        sourceChannel: 'redblack';
        channelMeta?: RedblackFeedbackChannelMeta | null;
      }
    | {
        sourceChannel: 'station_feedback';
        channelMeta?: StationFeedbackChannelMeta | null;
      }
  );

/** 经实时角色校验和成功查看审计后返回的来源只读地址。 */
export interface FeedbackExternalLink {
  /** 仅保证绝对 HTTPS URI、非空 host 且无 user-info；当前不代表已配置 host allowlist。 */
  url: string;
  channel: 'JIRA' | 'REDBLACK';
  readOnly: true;
}

export function getFeedbackDetail(id: number): Promise<FeedbackDetail> {
  return get(`/feedback/${id}`);
}

/** 反馈附件摘要；除 id 外均可能被字段策略省略，真实内容仍通过登录态内容流读取。 */
export interface FeedbackAttachmentSummary {
  id: number;
  attachmentType?: string;
  mimeType?: string | null;
  fileSizeBytes?: number | null;
  width?: number | null;
  height?: number | null;
  durationSeconds?: number | null;
  uploadStatus?: string | null;
  securityStatus?: string | null;
  integrityStatus?: string | null;
  decryptionStatus?: string | null;
  malwareScanStatus?: string | null;
  contentReviewStatus?: string | null;
  sourceKind?: string | null;
  retentionUntil?: string | null;
  createdAt?: string | null;
  available?: boolean;
  /** 私有派生媒体预览状态；不包含永久 URL。 */
  previewStatus?: 'PENDING' | 'PROCESSING' | 'AVAILABLE' | 'FAILED' | null;
}

export function getFeedbackAttachments(
  id: number,
  signal?: AbortSignal,
): Promise<FeedbackAttachmentSummary[]> {
  return get(`/feedback/${id}/attachments`, signal ? { signal } : undefined);
}

export function getFeedbackExternalLink(
  id: number,
): Promise<FeedbackExternalLink> {
  return get(`/feedback/${id}/external-link`);
}

export interface AttachmentAccessGrant {
  /** 一次性授权标识；只用于同源受控流，不得持久化或写入日志。 */
  grantId: string;
  /** Asia/Shanghai 授权失效时间。 */
  expiresAt: string;
  /** 授权剩余秒数，服务端约束为 30～300 秒。 */
  expiresInSeconds: number;
  /** 页面查看流与原始日志下载流用途隔离，令牌不可互换。 */
  delivery: 'ONE_TIME_SECURE_STREAM' | 'ONE_TIME_DERIVED_PREVIEW' | 'ONE_TIME_ORIGINAL_DOWNLOAD';
  /** 同源临时流地址，不是永久附件地址。 */
  downloadUrl: string;
}

export function authorizeFeedbackAttachment(
  id: number,
  attachmentId: number,
  signal?: AbortSignal,
): Promise<AttachmentAccessGrant> {
  return post(
    `/feedback/${id}/attachments/${attachmentId}/authorize`,
    undefined,
    signal ? { signal } : undefined,
  );
}

export function authorizeFeedbackAttachmentPreview(
  id: number,
  attachmentId: number,
  signal?: AbortSignal,
): Promise<AttachmentAccessGrant> {
  return post(
    `/feedback/${id}/attachments/${attachmentId}/authorize-preview`,
    undefined,
    signal ? { signal } : undefined,
  );
}

export interface AttachmentPreviewAuthorizationItem {
  attachmentId: number;
  status: 'AVAILABLE' | 'PENDING' | 'UNAVAILABLE';
  grant?: AttachmentAccessGrant | null;
}

export function authorizeFeedbackAttachmentPreviews(
  id: number,
  attachmentIds: number[],
  signal?: AbortSignal,
): Promise<{ items: AttachmentPreviewAuthorizationItem[] }> {
  return post(
    `/feedback/${id}/attachments/authorize-previews`,
    { attachmentIds },
    signal ? { signal, silent: true } as never : { silent: true } as never,
  );
}

export function authorizeOriginalLogDownload(
  id: number,
  attachmentId: number,
  signal?: AbortSignal,
): Promise<AttachmentAccessGrant> {
  return post(
    `/feedback/${id}/attachments/${attachmentId}/authorize-original-download`,
    undefined,
    signal ? { signal } : undefined,
  );
}

export function streamAuthorizedFeedbackAttachment(
  downloadUrl: string,
  options?: {
    timeoutMs?: number;
    signal?: AbortSignal;
    onProgress?: (loaded: number, total?: number) => void;
    previewBytes?: number;
  },
) {
  let relative = downloadUrl.startsWith('/api/v1')
    ? downloadUrl.slice('/api/v1'.length)
    : downloadUrl;
  if (options?.previewBytes) {
    const separator = relative.includes('?') ? '&' : '?';
    relative = `${relative}${separator}previewBytes=${Math.trunc(options.previewBytes)}`;
  }
  const config: AxiosRequestConfig & { silent: true } = {
    // 媒体文件可能超过全局 30 秒超时；调用方仍可设置明确上限，或在关闭页面时主动中止。
    timeout: options?.timeoutMs ?? 0,
    signal: options?.signal,
    silent: true,
    onDownloadProgress: (event: AxiosProgressEvent) =>
      options?.onProgress?.(event.loaded, event.total),
  };
  return download(relative, config);
}

export type FeedbackContentVisibility = 'RAW' | 'PARTIALLY_HIDDEN';

export type FeedbackDiagnosticSource =
  | 'GOVERNED_ATTACHMENT'
  | 'ENCRYPTED_VAULT'
  | 'SOURCE_REFERENCE'
  | 'AUTHENTICATED_SNAPSHOT'
  | 'ENCRYPTED_DIAGNOSTIC_VAULT';

/**
 * 按权限展示的诊断项。非日志项的 value 已按角色返回原文或局部隐藏值；GOVERNED_ATTACHMENT 的
 * value 只是安全元数据说明，正文通过 attachmentId 获取一次性安全流。
 */
export interface FeedbackDiagnosticItem {
  type: string;
  value?: string | null;
  source: FeedbackDiagnosticSource;
  attachmentId?: number | null;
  sourceAttachmentId?: string | number | null;
  /** 附件大小，单位：字节；非日志项或来源未知时为空。 */
  fileSizeBytes?: number | null;
  mimeType?: string | null;
  /** 查询时刻的安全快照；授权接口仍会实时复核全部状态。 */
  available?: boolean;
  securityStatus?: string | null;
  integrityStatus?: string | null;
  malwareScanStatus?: string | null;
  contentReviewStatus?: string | null;
}

/** 响应禁止缓存；任一解密、解析、展示或审计失败时服务端不会返回部分 diagnostics。 */
export interface FeedbackDiagnosticsResponse {
  sourceId: number;
  diagnostics: FeedbackDiagnosticItem[];
  contentVisibility: FeedbackContentVisibility;
  responseCacheable: false;
  usageRestriction: string;
}

export function viewFeedbackDiagnostics(
  id: number,
  signal?: AbortSignal,
): Promise<FeedbackDiagnosticsResponse> {
  return get(`/feedback/${id}/diagnostics`, signal ? { signal } : undefined);
}

export interface FeedbackTimelineEvent {
  /** 事件稳定标识。 */
  id: string;
  /** Asia/Shanghai 事件时间；来源没有可靠时间时为 null。 */
  time?: string | null;
  /** 事件名称。 */
  action?: string;
  /** 脱敏后的事件说明。 */
  detail?: string | null;
  /** 可展示的操作者名称；没有授权事实时为空。 */
  operator?: string | null;
  /** 当前固定为 primary。 */
  type?: 'primary';
}

export function getFeedbackTimeline(
  id: number,
): Promise<FeedbackTimelineEvent[]> {
  return get(`/feedback/${id}/timeline`);
}

/** 与当前反馈同属可见活动簇的安全投影；响应不会包含当前反馈自身。 */
export interface RelatedFeedbackItem {
  id: number;
  sourceChannel: FeedbackSourceChannel;
  sourceRecordId?: string | null;
  feedbackContent: string;
  occurredAt?: string | null;
  similarity?: number | null;
  decisionReason?: string | null;
  decisionVersion?: string | null;
  reviewStatus?: string | null;
  reviewVersion?: number | null;
  clusterId: number;
}

/** 服务端相关反馈分页；页面固定请求 10 条，服务端上限为 50 条。 */
export interface RelatedFeedbackPageResponse {
  records: RelatedFeedbackItem[];
  total: number;
  page: number;
  size: number;
}

export function getRelatedFeedback(
  id: number,
  page = 1,
  size = 10,
): Promise<RelatedFeedbackPageResponse> {
  return get(`/feedback/${id}/related`, { params: { page, size } });
}

export function exportFeedback(
  ids?: number[],
  params?: FeedbackListRequest,
): Promise<Blob> {
  return download('/feedback/export', {
    params: ids?.length ? { ids: ids.join(',') } : params,
  });
}
