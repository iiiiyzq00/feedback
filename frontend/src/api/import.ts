import { download, get, post } from "./request";

export type ImportChannel = "SDK" | "JIRA" | "QUALITY" | "REDBLACK";

export interface ImportUploadResult {
  batchId: string;
  status: string;
  duplicateResult: "NEW" | "EXACT_DUPLICATE" | "BUSINESS_DUPLICATE";
  totalRows: number;
  availableSheets: string[];
  headers: string[];
  preview: string[][];
  recommendedMapping: Record<string, string>;
  templateId?: number;
  templateVersion?: string;
  channelBoundary?: {
    declaredChannel: ImportChannel;
    detectedChannel?: ImportChannel;
    valid: boolean;
    reason?: string;
  };
  contentSha256?: string;
  normalizedContentSha256?: string;
  sheets?: Array<{ name: string; dataRows: number; columns: number }>;
  mappingConfidence?: Record<string, number>;
  simulatedSdk?: boolean;
  mappingMode?: "AUTO_VERSIONED" | "REVIEW_REQUIRED";
  mappingDecisionReason?: string;
  autoQueued?: boolean;
}

export interface ImportTemplateView {
  id: number;
  channel: ImportChannel;
  name: string;
  version: string;
  schemaVersion: string;
  headers: string[];
  mapping: Record<string, string>;
}

export function getImportTemplates(): Promise<ImportTemplateView[]> {
  return get("/import/templates");
}

export function downloadImportTemplate(channel: ImportChannel): Promise<Blob> {
  return download(`/import/templates/${channel}/download`);
}

export function exportImportReconciliation(batchId: string): Promise<Blob> {
  return download(`/import/batches/${batchId}/export`);
}
export function downloadImportErrors(batchId: string): Promise<Blob> {
  return download(`/import/batches/${batchId}/errors`);
}

export interface ImportBatchView {
  batchId: string;
  channel: ImportChannel;
  status: string;
  totalRows: number;
  successRows: number;
  insertedRows?: number;
  updatedRows?: number;
  failedRows: number;
  skippedRows: number;
  reviewedRows: number;
  createdAt: string;
  completedAt?: string;
  sourceDataUpdatedAt?: string;
  retryCount?: number;
  reconciliationStatus?: string;
  canRollback?: boolean;
  canRetry?: boolean;
  rollbackDeadline?: string;
  rawRetentionUntil?: string;
  ingestMethod?: "FILE" | "API";
  triggerType?: "MANUAL" | "SCHEDULED" | "BACKFILL";
  mappingVersion?: string;
  enumMappingVersion?: string;
  governanceStatus?: "ACTIVE" | "SUPERSEDED" | "NO_CHANGE";
  supersededByBatchId?: number;
  governanceReason?: string;
  governedAt?: string;
}
export interface ImportBatchPage {
  records: ImportBatchView[];
  total: number;
  page: number;
  size: number;
}
export interface ChannelQualityView {
  channel: ImportChannel | "STATION_FEEDBACK";
  totalCount: number;
  requiredCompletenessRate: number | null;
  primaryKeyDuplicateRate: number | null;
  timeValidRate: number | null;
  enumValidRate: number | null;
  maskingPassRate: number | null;
  importSuccessRate?: number;
  crossChannelSuspectedDuplicateRate: number | null;
  anomalyCount: number;
  quarantineCount: number;
  accessFailureCount: number;
  qualityScore: number | null;
  thresholdStatus: "PASS" | "ALERT" | "NO_DATA";
}
export function getChannelQuality(): Promise<ChannelQualityView[]> { return get("/import/quality/channels"); }
export function refreshChannelQuality(): Promise<ChannelQualityView[]> { return post("/import/quality/channels/refresh"); }
export interface JiraSyncStatus {
  enabled: boolean;
  ready: boolean;
  readinessErrors: string[];
  status: "DISABLED" | "IDLE" | "QUEUED" | "RUNNING" | "FAILED";
  watermark?: string;
  lastAttemptAt?: string;
  lastSuccessAt?: string;
  nextRunAt?: string;
  lastBatchId?: string;
  failureCount: number;
  lastErrorCode?: string;
  lastErrorDetail?: string;
  attachmentPendingCount: number;
  attachmentFailedCount: number;
}
export interface JiraSyncEnqueueResult { accepted: boolean; status: JiraSyncStatus }
export function getJiraSyncStatus():Promise<JiraSyncStatus>{return get("/import/jira/sync/status")}
export function enqueueJiraSync(input?:{from?:string;to?:string}):Promise<JiraSyncEnqueueResult>{return post("/import/jira/sync",input??{})}
export function retryJiraAttachmentTasks():Promise<{retried:number}>{return post("/import/jira/attachment-tasks/retry",{})}
export interface ImportBatchError {
  rowNumber: number;
  status?: string;
  errorCode: string;
  summary: string;
  suggestion?: string;
}
export function cleanupImportBatches(reason: string): Promise<{supersededBatches:number;noChangeBatches:number;activeBatches:number;preservedAuditFacts:boolean}> {
  return post("/import/batches/governance/cleanup", { reason });
}
export interface ImportBatchDetailView extends ImportBatchView {
  startedAt?: string;
  progressPercent: number;
  qualityMetrics?: {
    metricVersion?: string;
    qualityScore?: number;
    completenessRate?: number;
    mappingSuccessRate?: number;
    maskingPassRate?: number;
    duplicateRate?: number;
    invalidRate?: number;
    timeValidRate?: number;
    enumValidRate?: number;
    appMappingRate?: number;
    moduleMappingRate?: number;
    versionMappingRate?: number;
    importSuccessRate?: number;
    crossChannelSuspectedDuplicateRate?: number;
    anomalyCount?: number;
    quarantineCount?: number;
    thresholdStatus?: "PASS" | "ALERT";
  };
  errors?: ImportBatchError[];
  actions?: { rollbackDeadline?: string };
  lastErrorCode?: string;
  lastErrorDetail?: string;
}
export interface ImportBatchDetailResponse {
  batch?: ImportBatchDetailView;
  errors?: ImportBatchError[];
  progressPercent?: number;
}

export function uploadFile(
  file: File,
  channel: ImportChannel,
  sheetName?: string,
  dataDates?: [string, string],
): Promise<ImportUploadResult> {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("source_channel", channel);
  if (sheetName) formData.append("sheet_name", sheetName);
  if (dataDates?.[0]) formData.append("data_date_from", dataDates[0]);
  if (dataDates?.[1]) formData.append("data_date_to", dataDates[1]);
  return post("/import/upload", formData, {
    headers: { "Content-Type": "multipart/form-data" },
  });
}

export interface ImportChunkStatus {
  fileId: string;
  receivedChunks: number[];
  missingChunks: number[];
  isComplete: boolean;
  expiresAt: string;
  uploadStatus: "UPLOADING" | "ASSEMBLING" | "COMPLETED" | "FAILED" | "EXPIRED";
  batchId?: string;
}

export function uploadImportChunk(input: {
  fileId: string;
  channel: ImportChannel;
  chunkIndex: number;
  totalChunks: number;
  sha256: string;
  chunk: Blob;
}): Promise<ImportChunkStatus> {
  const formData = new FormData();
  formData.append("file_id", input.fileId);
  formData.append("source_channel", input.channel);
  formData.append("chunk_index", String(input.chunkIndex));
  formData.append("total_chunks", String(input.totalChunks));
  formData.append("chunk_sha256", input.sha256);
  formData.append("chunk_data", input.chunk, `chunk-${input.chunkIndex}.bin`);
  return post("/import/upload/chunk", formData, {
    headers: { "Content-Type": "multipart/form-data" },
  });
}

export function completeImportChunks(
  fileId: string,
  filename: string,
  fullSha256: string,
  sheetName?: string,
  dataDates?: [string, string],
): Promise<ImportUploadResult> {
  return (post(`/import/upload/chunk/${fileId}/complete`, {
    filename,
    fullSha256,
    sheetName,
    dataDateFrom: dataDates?.[0],
    dataDateTo: dataDates?.[1],
  }) as Promise<ImportChunkStatus>).then(async (initial) => {
    let state = initial;
    for (let attempt = 0; attempt < 300; attempt += 1) {
      if (state.uploadStatus === "COMPLETED" && state.batchId)
        return getImportMappingPreview(state.batchId);
      if (state.uploadStatus === "FAILED" || state.uploadStatus === "EXPIRED")
        throw new Error(`IMPORT_STAGING_${state.uploadStatus}`);
      await new Promise((resolve) => window.setTimeout(resolve, 1000));
      state = await get(`/import/upload/chunk/${fileId}`);
    }
    throw new Error("IMPORT_STAGING_TIMEOUT");
  });
}

export function getImportBatches(params?: {
  page?: number;
  size?: number;
  channel?: ImportChannel;
  status?: string;
  includeGoverned?: boolean;
}): Promise<ImportBatchPage> {
  return get("/import/batches", { params });
}

export function getImportBatchDetail(
  id: string,
): Promise<ImportBatchDetailResponse> {
  return get(`/import/batches/${id}`);
}
export function getImportMappingPreview(
  id: string,
): Promise<ImportUploadResult> {
  return get(`/import/batches/${id}/mapping-preview`);
}

export function confirmImport(
  batchId: string,
  mapping: Record<string, string>,
) {
  return post(`/import/batches/${batchId}/confirm`, { mapping });
}

export function rollbackImport(batchId: string) {
  return post(`/import/batches/${batchId}/rollback`);
}

export function retryImport(batchId: string) {
  return post(`/import/batches/${batchId}/retry`);
}
export function retryImportTechnicalFailures(batchId: string) {
  return post(`/import/batches/${batchId}/retry-technical`);
}
