import { del, download, get, post } from './request'
import type { FeedbackSourceChannel } from './feedback'

export function getReportList(params?: { type?: string; page?: number; size?: number }) {
  return get('/reports', { params })
}

export function getReportDetail(id: number) {
  return get(`/reports/${id}`)
}

export function generateReport(type: string, options: Record<string, any> = {}) {
  return post('/reports/generate', { type, ...options })
}

export function downloadReportPdf(id: number) { return download(`/reports/${id}/pdf`) }
export function deleteReport(id:number){return del(`/reports/${id}`)}
export function getReportTask(id:number){return get(`/reports/tasks/${id}`)}
export function getReportTasks(){return get('/reports/tasks')}
export function cancelReportTask(id:number){return post(`/reports/tasks/${id}/cancel`)}
export function retryReportTask(id:number){return post(`/reports/tasks/${id}/retry`)}

/** 来源于问题分析结果的成对时间范围；两者都省略时查询全部历史。 */
export interface ClusterTimeRangeRequest { startTime?: string; endTime?: string }
export type ClusterTimeRange = ClusterTimeRangeRequest

/** 当前权限、时间范围及固定 generation 下的问题簇详情。 */
export interface ClusterDetailResponse {
  id: number
  name: string
  canonicalSymptom: string
  functionModule: string | null
  issueCategory: string | null
  status: string
  lifecycleVersion: number
  mergedIntoClusterId: number | null
  splitFromClusterId: number | null
  statusReason: string | null
  totalFeedback: number
  channelCount: number
  latestTime: string | null
  firstTime: string | null
  activeDays: number
  uniqueUsers: number | null
  uniqueDevices: number
  stationCount: number
  jiraCount: number
  qualityCount: number
  redblackCount: number
}

export function getClusterDetail(id: number, params?: ClusterTimeRangeRequest): Promise<ClusterDetailResponse> {
  return get(`/reports/cluster/${id}`, { params })
}

/** 服务端稳定分页；正文和归簇解释均来自同一 generation 的安全投影。 */
export interface ClusterFeedbackPage {
  items: ClusterFeedbackItem[]
  total: number
  page: number
  size: number
}

/** 问题簇成员的安全展示合同，不包含来源原文或身份值。 */
export interface ClusterFeedbackItem {
  id: number
  sourceRecordId: string | null
  issueContent: string
  sourceChannel: FeedbackSourceChannel
  reportedAt: string | null
  controlledInfoTypes: string | null
  attachmentCount: number
  screenshotCount: number
  logCount: number
  videoCount: number
  decisionType: string | null
  similarity: number | null
  decisionReason: string | null
  aiModel: string | null
  aiConfidence: number | null
  aiAction: string | null
  aiExplanation: string | null
}

/** 簇内反馈页请求；页面固定使用 20 条，服务端合同允许 1 至 100 条。 */
export interface ClusterFeedbackRequest extends ClusterTimeRangeRequest { page: number; size: number }

export function getClusterFeedback(clusterId: number, params: ClusterFeedbackRequest): Promise<ClusterFeedbackPage> {
  return get(`/reports/cluster/${clusterId}/feedback`, { params })
}

export function getClusterRca(clusterId: number) {
  return get(`/reports/cluster/${clusterId}/rca`)
}

export function getClusterKnowledge(clusterId: number) {
  return get(`/reports/cluster/${clusterId}/knowledge`)
}

export function getClusterIncidents(clusterId: number) {
  return get(`/reports/cluster/${clusterId}/incidents`)
}
export interface FeedbackRcaKnowledgeCluster {
  id: number
  name?: string | null
}

export interface FeedbackRcaKnowledgeRca {
  id: number
  rootCause?: string | null
  status?: string | null
  createdAt?: string | null
}

export interface FeedbackRcaKnowledgeItem {
  id: number
  title?: string | null
  content?: string | null
  status?: string | null
  adoptionCount?: number | null
}

/** 非反馈账号可见的兼容区块响应；反馈账号不会请求该接口。 */
export interface FeedbackRcaKnowledgeResponse {
  cluster: FeedbackRcaKnowledgeCluster | null
  rca: FeedbackRcaKnowledgeRca[]
  knowledge: FeedbackRcaKnowledgeItem[]
}

export function getFeedbackRcaKnowledge(feedbackId: number): Promise<FeedbackRcaKnowledgeResponse> {
  return get(`/reports/feedback/${feedbackId}/rca-knowledge`)
}
