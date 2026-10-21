export const INLINE_TEXT_LIMIT_BYTES = 20 * 1024 * 1024

export interface GovernedAttachmentLike {
  attachmentId?: number | null
  id?: number | null
  sourceAttachmentId?: string | number | null
  fileSizeBytes?: number | null
  mimeType?: string | null
  attachmentType?: string | null
  filename?: string | null
}

export function isInlineTextAttachment(item: GovernedAttachmentLike) {
  const size = Number(item.fileSizeBytes)
  return item.fileSizeBytes != null && Number.isFinite(size) && size >= 0
    && String(item.mimeType || '').toLowerCase() === 'text/plain'
    && size <= INLINE_TEXT_LIMIT_BYTES
}

const LOG_ARCHIVE_MIME_TYPES = new Set([
  'application/gzip',
  'application/x-gzip',
  'application/zip',
  'application/x-zip-compressed',
  'application/x-tar',
])

/** 识别日志与受支持的日志归档。原始下载另有独立的授权判定。 */
export function isGovernedLogAttachment(item: GovernedAttachmentLike) {
  if (String(item.attachmentType || '').toUpperCase() === 'LOG') return true
  const mimeType = String(item.mimeType || '').toLowerCase()
  const filename = String(item.filename || item.sourceAttachmentId || '').toLowerCase()
  return mimeType === 'text/plain'
    || LOG_ARCHIVE_MIME_TYPES.has(mimeType)
    || /\.(?:log|txt|gz|gzip|zip|tar|tgz)$/.test(filename)
}

export function controlledDownloadTimeoutMs(fileSizeBytes?: number | null) {
  // 页面预览必须有结束边界。大文件按压缩包大小获得少量宽限，但最多等待 90 秒；
  // 原始文件下载由调用方显式传 timeoutMs: 0，不受此预览超时影响。
  const bytes = Number(fileSizeBytes || 0)
  const sizeAllowance = Number.isFinite(bytes) && bytes > 0
    ? Math.ceil(bytes / (1024 * 1024)) * 750
    : 0
  return Math.min(90_000, Math.max(30_000, 30_000 + sizeAllowance))
}

export function controlledDownloadProgress(loaded: number, total?: number, _expectedSize?: number | null) {
  // 转换后的日志流已在服务端解密/解压，因此没有
  // 可信的 Content-Length。绝不能把解压后的字节数与压缩后的
  // 附件大小相比：那个长居不下的「99%」就是这么造出来的。
  const denominator = Number(total || 0)
  const received = Number(loaded)
  if (!Number.isFinite(denominator) || denominator <= 0 || !Number.isFinite(received)) return null
  return Math.min(99, Math.max(0, Math.floor(received * 100 / denominator)))
}

export type ControlledDownloadStage = 'authorizing' | 'streaming' | 'parsing'

export function controlledDownloadStatusText(stage: ControlledDownloadStage, loadedBytes = 0, progress: number | null = null) {
  if (stage === 'authorizing') return '准备查看'
  if (stage === 'parsing') return '正在生成预览'
  if (progress != null) return `加载中 ${progress}%`
  if (loadedBytes <= 0) return '服务端解压中'
  const megabytes = loadedBytes / (1024 * 1024)
  return `服务端解压中 · 已接收 ${megabytes < 10 ? megabytes.toFixed(1) : Math.round(megabytes)} MB`
}

export function controlledAttachmentFilename(item: GovernedAttachmentLike, prefix = 'application-log') {
  const mime = String(item.mimeType || '').toLowerCase()
  const extension = mime === 'application/gzip' || mime === 'application/x-gzip' ? '.gz'
    : mime === 'application/zip' || mime === 'application/x-zip-compressed' ? '.zip'
      : mime === 'text/plain' ? '.log' : '.bin'
  return `${prefix}-${item.sourceAttachmentId || item.attachmentId || item.id || 'download'}${extension}`
}

export function saveControlledBlob(blob: Blob, filename: string, revokeDelayMs = 1_000) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  window.setTimeout(() => {
    anchor.remove()
    URL.revokeObjectURL(url)
  }, revokeDelayMs)
}

export function controlledDownloadErrorMessage(error: unknown) {
  const value = error as { code?: string; message?: string; response?: { status?: number } }
  if (value?.code === 'ERR_CANCELED') return null
  const status = Number(value?.response?.status || 0)
  if (value?.code === 'ECONNABORTED' || String(value?.message || '').toLowerCase().includes('timeout')) {
    return '日志查看超时，请检查网络后重试'
  }
  if (status === 401 || status === 403) return '日志查看授权已失效或当前账号无权限，请重试'
  if (status === 404) return '日志文件缺失、已清理或当前不可用'
  if (status === 409 || status === 422) return '日志安全检查尚未通过，当前不允许查看'
  if (status >= 500 || status === 0) return '日志服务连接异常，请稍后重试'
  return '日志查看失败，请重试'
}

export function controlledEvidenceErrorMessage(error: unknown, label: '截图' | '视频' | '附件') {
  const value = error as { code?: string; message?: string; response?: { status?: number } }
  if (value?.code === 'ERR_CANCELED') return null
  const status = Number(value?.response?.status || 0)
  if (value?.code === 'ECONNABORTED' || String(value?.message || '').toLowerCase().includes('timeout')) {
    return `${label}加载超时，请检查网络后重试`
  }
  if (status === 401 || status === 403) return `${label}查看授权已失效或当前账号无权限，请重试`
  if (status === 404) return `${label}文件缺失、已清理或当前不可用`
  if (status === 409 || status === 422) return `${label}安全检查尚未通过，当前不允许查看`
  if (status >= 500 || status === 0) return `${label}服务连接异常，请稍后重试`
  return `${label}加载失败，请重试`
}
