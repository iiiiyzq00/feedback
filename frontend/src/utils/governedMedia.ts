import type { FeedbackAttachmentSummary } from '@/api/feedback'

export type GovernedMediaKind = 'IMAGE' | 'VIDEO'

const IMAGE_MIME_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
// 这份白名单必须与 LocalAttachmentContentReviewer 保持一致。QuickTime 是已评审的
// 视频封装格式；具体编码支持随后由浏览器的视频解码器决定。
const VIDEO_MIME_TYPES = new Set(['video/mp4', 'video/quicktime', 'video/webm'])
const IMAGE_ATTACHMENT_TYPES = new Set(['IMAGE', 'SCREENSHOT', 'ANNOTATED_SCREENSHOT'])
const VIDEO_ATTACHMENT_TYPES = new Set(['VIDEO', 'SCREEN_RECORDING'])

/**
 * 只有当声明的附件族与 MIME 契约都安全时，才返回可渲染类型。
 * 刻意禁止宽泛的 image/* 与 video/* 匹配：SVG、HTML 与不稳定编码一律按不可渲染处理。
 */
export function governedMediaKind(item: FeedbackAttachmentSummary): GovernedMediaKind | undefined {
  const attachmentType = String(item.attachmentType || '').toUpperCase()
  const mimeType = normalizedMimeType(item.mimeType)
  if (IMAGE_ATTACHMENT_TYPES.has(attachmentType) && IMAGE_MIME_TYPES.has(mimeType)) return 'IMAGE'
  if (VIDEO_ATTACHMENT_TYPES.has(attachmentType) && VIDEO_MIME_TYPES.has(mimeType)) return 'VIDEO'
  return undefined
}

export function isAllowedGovernedMime(kind: GovernedMediaKind, mimeType?: string | null) {
  const normalized = normalizedMimeType(mimeType)
  return kind === 'IMAGE' ? IMAGE_MIME_TYPES.has(normalized) : VIDEO_MIME_TYPES.has(normalized)
}

/** 流式响应必须与已授权元数据一致，之后才允许浏览器解码器接触它。 */
export function governedBlobMatches(item: FeedbackAttachmentSummary, blob: Blob) {
  const kind = governedMediaKind(item)
  if (!kind) return false
  return normalizedMimeType(blob.type) === normalizedMimeType(item.mimeType)
    && isAllowedGovernedMime(kind, blob.type)
}

export function boundedThumbnailSize(width: number, height: number, maxEdge = 240, maxPixels = 57_600) {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return undefined
  const edgeScale = Math.min(1, maxEdge / Math.max(width, height))
  const pixelScale = Math.min(1, Math.sqrt(maxPixels / (width * height)))
  const scale = Math.min(edgeScale, pixelScale)
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

/**
 * 避开常见的片头黑帧，同时让长录制的缩略图定位保持廉价。
 * 极短片段取中点；普通片段取靠前的代表性帧。
 */
export function videoThumbnailSeekTime(duration: number) {
  if (!Number.isFinite(duration) || duration <= 0) return 0
  if (duration <= 1) return duration / 2
  return Math.min(duration - 0.1, Math.max(1, Math.min(5, duration * 0.1)))
}

function normalizedMimeType(value?: string | null) {
  return String(value || '').split(';', 1)[0].trim().toLowerCase()
}
