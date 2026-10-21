import { get } from './request'

export interface ShadowBoundaryCoverage {
  application: number
  failureMode: number
  target: number
  action: number
  errorSignature: number
  conflictCount: number
}

export interface ShadowClusterItem {
  previewId: string
  problem: string
  phenomenon: string
  memberCount: number
  application: string
  provisional: boolean
  provisionalMemberCount: number
  large: boolean
  crossChannel: boolean
  channels: Array<{ channel: string; count: number }>
  boundaries: ShadowBoundaryCoverage
}

export interface ShadowAnalysisSection {
  key: 'CONFIRMED_APPLICATION' | 'PENDING_APPLICATION' | 'ENGINEERING_CLASSIFICATION' | 'UNUSABLE_CONTENT'
  label: string
  recordCount: number
  clusterCount: number | null
  detailAvailable: boolean
  explanation: string
}

export interface ShadowGeneration {
  generationKey: string
  status: 'VERIFIED'
  algorithmVersion: string
  modelVersion: string
  inputCount: number
  actionableCount: number
  terminalCount: number
  clusterCount: number
  provisionalCount: number
  acceptThreshold: number
  provisionalThreshold: number
  semanticWeight: number
  verifiedAt: string | null
  terminalReasons: Array<{
    status: string
    label: string
    count: number
    explanation: string
  }>
}

export interface ShadowPreviewPage {
  generation: ShadowGeneration
  analysisSections: ShadowAnalysisSection[]
  items: ShadowClusterItem[]
  total: number
  page: number
  size: number
  generatedAt: string
  readOnly: true
}

export interface ShadowMember {
  anonymousId: string
  channel: string
  application: string
  decision: string
  score: number | null
  provisional: boolean
  boundaryKnown: Record<'application' | 'failureMode' | 'target' | 'action' | 'errorSignature', boolean>
}

export interface ShadowClusterDetail {
  cluster: ShadowClusterItem
  members: ShadowMember[]
  total: number
  page: number
  size: number
  bodyProjectionAvailable: false
  bodyProjectionReason: string
}

export function getShadowPreview(params: {
  page: number
  size: number
  largeOnly?: boolean
  provisionalOnly?: boolean
  crossChannelOnly?: boolean
  application?: string
  analysisSection?: 'CONFIRMED_APPLICATION' | 'PENDING_APPLICATION'
}): Promise<ShadowPreviewPage> {
  return get('/admin/cluster-shadow-preview', { params })
}

export function getShadowClusterDetail(previewId: string, page: number, size: number): Promise<ShadowClusterDetail> {
  return get(`/admin/cluster-shadow-preview/clusters/${encodeURIComponent(previewId)}`, { params: { page, size } })
}
