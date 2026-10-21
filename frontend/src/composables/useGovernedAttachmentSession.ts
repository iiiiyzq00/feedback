import { onBeforeUnmount } from 'vue'
import {
  authorizeFeedbackAttachmentPreviews,
  type AttachmentPreviewAuthorizationItem,
} from '@/api/feedback'

const configuredConcurrency = Number(import.meta.env.VITE_ATTACHMENT_PREVIEW_CONCURRENCY ?? 3)
const MAX_AUTOMATIC_PREVIEW_CONCURRENCY = Number.isInteger(configuredConcurrency)
  ? Math.min(6, Math.max(1, configuredConcurrency))
  : 3
let activeAutomaticPreviews = 0
const automaticPreviewQueue: Array<() => void> = []

interface QueuedPreviewAuthorization {
  sourceId: number
  attachmentId: number
  signal: AbortSignal
  resolve: (item: AttachmentPreviewAuthorizationItem) => void
  reject: (error: unknown) => void
}

let authorizationQueue: QueuedPreviewAuthorization[] = []
let authorizationFlushScheduled = false

/** 把跨表格行的视口进入事件合并成有界的、同来源的授权调用。 */
export function requestGovernedPreviewAuthorization(
  sourceId: number,
  attachmentId: number,
  signal: AbortSignal,
): Promise<AttachmentPreviewAuthorizationItem> {
  if (signal.aborted) return Promise.reject(new DOMException('缩略图加载已取消', 'AbortError'))
  const result = new Promise<AttachmentPreviewAuthorizationItem>((resolve, reject) => {
    authorizationQueue.push({ sourceId, attachmentId, signal, resolve, reject })
  })
  if (!authorizationFlushScheduled) {
    authorizationFlushScheduled = true
    queueMicrotask(flushPreviewAuthorizations)
  }
  return result
}

async function flushPreviewAuthorizations() {
  authorizationFlushScheduled = false
  const queued = authorizationQueue
  authorizationQueue = []
  queued.filter(item => item.signal.aborted).forEach(item => item.reject(new DOMException('缩略图加载已取消', 'AbortError')))
  const active = queued.filter(item => !item.signal.aborted)
  const sourceIds = [...new Set(active.map(item => item.sourceId))]
  await Promise.all(sourceIds.map(async (sourceId) => {
    const group = active.filter(item => item.sourceId === sourceId)
    const ids = [...new Set(group.map(item => item.attachmentId))]
    for (let offset = 0; offset < ids.length; offset += 20) {
      const chunk = ids.slice(offset, offset + 20)
      const members = group.filter(item => chunk.includes(item.attachmentId))
      try {
        const response = await authorizeFeedbackAttachmentPreviews(sourceId, chunk)
        const byId = new Map(response.items.map(item => [item.attachmentId, item]))
        members.forEach(item => {
          if (item.signal.aborted) item.reject(new DOMException('缩略图加载已取消', 'AbortError'))
          else item.resolve(byId.get(item.attachmentId) ?? { attachmentId: item.attachmentId, status: 'UNAVAILABLE' })
        })
      } catch (error) {
        members.forEach(item => item.reject(error))
      }
    }
  }))
}

function drainAutomaticPreviewQueue() {
  while (activeAutomaticPreviews < MAX_AUTOMATIC_PREVIEW_CONCURRENCY && automaticPreviewQueue.length) {
    automaticPreviewQueue.shift()?.()
  }
}

/** 限制所有可见表格行上被动生成的缩略图工作量。 */
export function withGovernedPreviewSlot<T>(task: () => Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let started = false

    const abortQueuedTask = () => {
      if (started) return
      const index = automaticPreviewQueue.indexOf(start)
      if (index >= 0) automaticPreviewQueue.splice(index, 1)
      reject(new DOMException('缩略图加载已取消', 'AbortError'))
    }

    const start = () => {
      if (signal.aborted) {
        abortQueuedTask()
        return
      }
      started = true
      activeAutomaticPreviews += 1
      signal.removeEventListener('abort', abortQueuedTask)
      task().then(resolve, reject).finally(() => {
        activeAutomaticPreviews -= 1
        drainAutomaticPreviewQueue()
      })
    }

    signal.addEventListener('abort', abortQueuedTask, { once: true })
    automaticPreviewQueue.push(start)
    drainAutomaticPreviewQueue()
  })
}

/**
 * 统一持有查看受治理证据期间产生的全部临时资源。
 * 取消会话是对话框、路由切换与组件销毁共用的唯一清理边界；
 * 敏感的 Blob URL 绝不会活过这个边界。
 */
export function useGovernedAttachmentSession() {
  const controllers = new Set<AbortController>()
  const objectUrls = new Set<string>()

  function createController() {
    const controller = new AbortController()
    controllers.add(controller)
    return controller
  }

  function releaseController(controller: AbortController) {
    controllers.delete(controller)
  }

  function createObjectUrl(blob: Blob) {
    const url = URL.createObjectURL(blob)
    objectUrls.add(url)
    return url
  }

  function revokeObjectUrl(url?: string) {
    if (!url || !objectUrls.delete(url)) return
    URL.revokeObjectURL(url)
  }

  function cancelAll() {
    cancelRequests()
    objectUrls.forEach((url) => URL.revokeObjectURL(url))
    objectUrls.clear()
  }

  function cancelRequests() {
    controllers.forEach((controller) => controller.abort())
    controllers.clear()
  }

  onBeforeUnmount(cancelAll)

  return {
    createController,
    releaseController,
    createObjectUrl,
    revokeObjectUrl,
    cancelRequests,
    cancelAll,
  }
}
