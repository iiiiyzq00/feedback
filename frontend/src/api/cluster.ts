import { get, post } from './request'

export interface ReviewCommand { expectedVersion: number; requestId: string; reason: string }

export type JsonEvidence = Record<string, unknown> | string | null
export type ClusterReviewPriority = 'P1' | 'P2' | 'P3'
export interface ClusterCandidate {
  clusterId: number; clusterName: string; similarity: number; rank: number; recommended: boolean
  feedbackCount: number; product?: string | null; module?: string | null; issueCategory?: string | null; clusterStatus?: string | null
  lifecycleVersion?: number | null; canonicalSymptom?: string | null; canonicalObject?: string | null; canonicalTrigger?: string | null
  errorSignature?: string | null; evidence?: JsonEvidence
}
export interface ClusterReviewItem {
  id: number; sourceRecordId?: string | null; title?: string | null; description?: string | null; priority: ClusterReviewPriority
  sourceChannel?: string | null; severity?: string | null; feedbackModule?: string | null; issueCategory?: string | null
  decision?: string | null; decisionReason?: string | null; decisionVersion?: string | null; decisionConfigVersion?: string | null
  reviewVersion: number; similarity: number; candidateCount?: number; candidates: ClusterCandidate[]
  overdue?: boolean; slaEnabled?: boolean; slaRemainingSeconds?: number; featureSnapshot?: JsonEvidence
}
export interface ClusterReviewPage { records: ClusterReviewItem[]; total: number; page?: number; size?: number }

export function getClusterReviews(params: { priority?: string; page?: number; size?: number }): Promise<ClusterReviewPage> {
  return get('/cluster/review', { params })
}
export function getClusterReviewPolicy() { return get('/cluster/review/policy') }

export function confirmCluster(sourceId: number, clusterId: number, command: ReviewCommand) {
  return post(`/cluster/review/${sourceId}/confirm`, { clusterId, ...command })
}

export function createCluster(sourceId: number, name: string, command: ReviewCommand) {
  return post('/cluster/review/create', { sourceId, name, ...command })
}

export function rejectCluster(sourceId: number, command: ReviewCommand) {
  return post(`/cluster/review/${sourceId}/reject`, command)
}

export function skipCluster(sourceId: number, command: ReviewCommand) {
  return post(`/cluster/review/${sourceId}/skip`, command)
}

export function unlinkCluster(sourceId: number, command: ReviewCommand) {
  return post(`/cluster/review/${sourceId}/unlink`, command)
}

export function changeClusterStatus(clusterId: number, status: string, command: ReviewCommand) {
  return post(`/cluster/${clusterId}/status`, { status, ...command })
}

export function mergeClusters(targetId: number, sourceClusterIds: number[], command: ReviewCommand) {
  return post(`/cluster/${targetId}/merge`, { sourceClusterIds, ...command })
}

export function splitCluster(clusterId: number, partitions: Array<{ name: string; sourceIds: number[] }>, command: ReviewCommand) {
  return post(`/cluster/${clusterId}/split`, { partitions, ...command })
}

export function reopenCluster(clusterId: number, recurrenceSourceId: number, command: ReviewCommand) {
  return post(`/cluster/${clusterId}/reopen`, { recurrenceSourceId, ...command })
}
