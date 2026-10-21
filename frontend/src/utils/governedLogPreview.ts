export const GOVERNED_LOG_PREVIEW_LIMIT_BYTES = 5 * 1024 * 1024
export const GOVERNED_LOG_TRUNCATION_MARKER = '===== 日志预览已截断 ====='

export interface GovernedLogPreviewResult {
  lines: string[]
  receivedBytes: number
  truncated: boolean
}

export interface GovernedLogPreviewOptions {
  signal?: AbortSignal
  onProgress?: (receivedBytes: number) => void
  yieldEveryBytes?: number
  yieldControl?: () => Promise<void>
}

function abortError() {
  return new DOMException('日志解析已取消', 'AbortError')
}

function ensureActive(signal?: AbortSignal) {
  if (signal?.aborted) throw abortError()
}

function defaultYieldControl() {
  return new Promise<void>((resolve) => window.setTimeout(resolve, 0))
}

function readBlobWithFileReader(blob: Blob, signal?: AbortSignal) {
  return new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader()
    const abort = () => reader.abort()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error ?? new Error('日志读取失败'))
    reader.onabort = () => reject(abortError())
    signal?.addEventListener('abort', abort, { once: true })
    reader.onloadend = () => signal?.removeEventListener('abort', abort)
    reader.readAsArrayBuffer(blob)
  })
}

async function* readBlobChunks(blob: Blob, signal?: AbortSignal): AsyncGenerator<Uint8Array> {
  ensureActive(signal)
  if (typeof blob.stream !== 'function') {
    yield new Uint8Array(await readBlobWithFileReader(blob, signal))
    ensureActive(signal)
    return
  }

  const reader = blob.stream().getReader()
  try {
    while (true) {
      ensureActive(signal)
      const { done, value } = await reader.read()
      if (done) return
      yield value
    }
  } catch (error) {
    await reader.cancel().catch(() => undefined)
    throw error
  } finally {
    reader.releaseLock()
  }
}

/**
 * 增量解码服务端的有界响应。收到的 Blob 仍由浏览器持有，
 * 但解码与行拼接会在批次之间让出主线程，
 * 这样一个 5 MB 的预览不会占满 UI 线程。
 */
export async function parseGovernedLogBlob(
  blob: Blob,
  options: GovernedLogPreviewOptions = {},
): Promise<GovernedLogPreviewResult> {
  const decoder = new TextDecoder('utf-8')
  const lines: string[] = []
  const yieldEveryBytes = options.yieldEveryBytes ?? 256 * 1024
  const yieldControl = options.yieldControl ?? defaultYieldControl
  let pendingLine = ''
  let receivedBytes = 0
  let bytesSinceYield = 0

  for await (const value of readBlobChunks(blob, options.signal)) {
    receivedBytes += value.byteLength
    bytesSinceYield += value.byteLength
    options.onProgress?.(receivedBytes)

    const decoded = pendingLine + decoder.decode(value, { stream: true })
    const chunks = decoded.split('\n')
    pendingLine = chunks.pop() ?? ''
    for (const line of chunks) lines.push(line.endsWith('\r') ? line.slice(0, -1) : line)

    if (bytesSinceYield >= yieldEveryBytes) {
      bytesSinceYield = 0
      await yieldControl()
    }
  }

  pendingLine += decoder.decode()
  if (pendingLine || lines.length === 0) lines.push(pendingLine.endsWith('\r') ? pendingLine.slice(0, -1) : pendingLine)
  ensureActive(options.signal)

  return {
    lines,
    receivedBytes,
    truncated: lines.some((line) => line.trim() === GOVERNED_LOG_TRUNCATION_MARKER),
  }
}

export function formatPreviewBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${Math.floor(bytes)} B`
  const kilobytes = bytes / 1024
  if (kilobytes < 1024) return `${kilobytes.toFixed(kilobytes < 10 ? 1 : 0)} KB`
  const megabytes = kilobytes / 1024
  return `${megabytes.toFixed(megabytes < 10 ? 1 : 0)} MB`
}
