<template>
  <section v-if="evidenceItems.length" ref="root" class="governed-evidence" :class="`governed-evidence--${mode}`">
    <button
      v-for="item in evidenceItems"
      :key="item.key"
      :ref="(element) => registerElement(item.key, element)"
      type="button"
      class="evidence-thumbnail"
      :class="[`evidence-thumbnail--${item.kind.toLowerCase()}`, `is-${item.status}`]"
      :data-evidence-key="item.key"
      :aria-label="accessibleLabel(item)"
      :title="accessibleLabel(item)"
      @click.stop="activate(item)"
    >
      <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" alt="" aria-hidden="true">
      <pre v-else-if="item.kind === 'LOG'" class="evidence-thumbnail__log">{{ logThumbnailText(item) }}</pre>
      <span v-else-if="item.kind === 'OTHER'" class="evidence-thumbnail__file" aria-hidden="true">◇</span>
      <span v-else class="evidence-thumbnail__state" aria-hidden="true">
        {{ item.status === 'loading' || item.status === 'queued' ? '◌' : item.status === 'error' ? '!' : '◫' }}
      </span>
      <span v-if="item.kind === 'VIDEO'" class="evidence-thumbnail__play" aria-hidden="true">▶</span>
    </button>
  </section>

  <el-dialog
    v-model="mediaDialogVisible"
    :title="activeItem?.kind === 'VIDEO' ? '视频证据查看' : '图片证据查看'"
    width="min(960px, calc(100vw - 24px))"
    append-to-body
    destroy-on-close
    @closed="closeDialog"
  >
    <div
      class="media-dialog"
      v-loading="activeItem?.status === 'loading' || activeItem?.status === 'queued' || activeItem?.status === 'idle'"
      element-loading-text="正在加载证据"
    >
      <img
        v-if="activeItem?.status === 'ready' && activeItem.kind === 'IMAGE' && activeItem.contentUrl"
        :src="activeItem.contentUrl"
        :alt="`图片附件 ${activeItem.attachment?.id}`"
        @error="markUnsupported(activeItem)"
      >
      <video
        v-else-if="activeItem?.status === 'ready' && activeItem.kind === 'VIDEO' && activeItem.contentUrl"
        :src="activeItem.contentUrl"
        controls
        autoplay
        preload="metadata"
        @error="markUnsupported(activeItem)"
      />
      <div
        v-else-if="activeItem?.status === 'loading' || activeItem?.status === 'queued' || activeItem?.status === 'idle'"
        class="media-dialog__skeleton"
        aria-label="证据正在加载"
      />
      <el-result
        v-else-if="activeItem"
        icon="warning"
        :title="activeItem?.status === 'unsupported' ? '当前格式不支持页面预览' : '证据暂不可查看'"
        :sub-title="activeItem?.error"
      >
        <template #extra>
          <el-button v-if="activeItem.status === 'error'" type="primary" @click="retryContent(activeItem)">重试</el-button>
        </template>
      </el-result>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, type ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import {
  authorizeFeedbackAttachment,
  getFeedbackAttachments,
  streamAuthorizedFeedbackAttachment,
  type FeedbackAttachmentSummary,
  type FeedbackDiagnosticItem,
} from '@/api/feedback'
import {
  requestGovernedPreviewAuthorization,
  useGovernedAttachmentSession,
  withGovernedPreviewSlot,
} from '@/composables/useGovernedAttachmentSession'
import { controlledDownloadTimeoutMs, controlledEvidenceErrorMessage } from '@/utils/controlledAttachmentDownload'
import {
  governedBlobMatches,
  governedMediaKind,
  type GovernedMediaKind,
} from '@/utils/governedMedia'
import { parseGovernedLogBlob } from '@/utils/governedLogPreview'

type EvidenceKind = GovernedMediaKind | 'LOG' | 'OTHER'
type EvidenceStatus = 'idle' | 'queued' | 'loading' | 'preview-pending' | 'ready' | 'unavailable' | 'error' | 'unsupported'

interface EvidenceItem {
  key: string
  kind: EvidenceKind
  status: EvidenceStatus
  attachment?: FeedbackAttachmentSummary
  log?: FeedbackDiagnosticItem
  contentUrl?: string
  thumbnailUrl?: string
  previewText?: string
  previewRetryCount?: number
  error?: string
}

interface EvidenceCounts {
  attachment?: number | null
  image?: number | null
  video?: number | null
  log?: number | null
}

const props = withDefaults(defineProps<{
  sourceId: number
  attachments?: FeedbackAttachmentSummary[]
  logAttachments?: FeedbackDiagnosticItem[]
  counts?: EvidenceCounts
  mode?: 'compact' | 'detail'
}>(), {
  attachments: undefined,
  logAttachments: () => [],
  counts: () => ({}),
  mode: 'compact',
})

const emit = defineEmits<{ 'view-log': [FeedbackDiagnosticItem] }>()
const root = ref<HTMLElement>()
const summaries = ref<FeedbackAttachmentSummary[]>(props.attachments ? [...props.attachments] : [])
const summariesSkippedByZeroCount = ref(props.attachments === undefined && hasExplicitZeroEvidenceCounts())
const summariesLoaded = ref(props.attachments !== undefined || summariesSkippedByZeroCount.value)
const session = useGovernedAttachmentSession()
const evidenceItems = reactive<EvidenceItem[]>([])
const mediaDialogVisible = ref(false)
const activeItem = ref<EvidenceItem>()
const elements = new Map<string, Element>()
const contentLoads = new Map<string, Promise<Blob>>()
const thumbnailLoads = new Map<string, Promise<void>>()
const previewRetryTimers = new Map<string, number>()
let observer: IntersectionObserver | undefined
let generation = 0
const LOG_THUMBNAIL_BYTES = 4 * 1024
const LOG_THUMBNAIL_CHARACTERS = 360

function hasExplicitZeroEvidenceCounts() {
  const values = [
    props.counts.attachment,
    props.counts.image,
    props.counts.video,
    props.counts.log,
  ]
  const declaredValues = values.filter((value) => value != null && Number.isFinite(Number(value)))
  return declaredValues.length > 0 && declaredValues.every((value) => Number(value) <= 0)
}

function rebuildItems(preserveLoaded = false) {
  const previous = new Map(evidenceItems.map(item => [item.key, item]))
  if (preserveLoaded) session.cancelRequests()
  else session.cancelAll()
  clearPreviewRetryTimers()
  contentLoads.clear()
  thumbnailLoads.clear()
  evidenceItems.splice(0, evidenceItems.length)
  const diagnosticsByAttachmentId = new Map(
    props.logAttachments
      .filter((item) => item.attachmentId != null)
      .map((item) => [item.attachmentId!, item]),
  )
  const renderedLogAttachmentIds = new Set<number>()
  for (const attachment of summaries.value) {
    const mediaKind = governedMediaKind(attachment)
    const declaredType = String(attachment.attachmentType || '').toUpperCase()
    if (declaredType === 'LOG') {
      const diagnostic = diagnosticsByAttachmentId.get(attachment.id)
      renderedLogAttachmentIds.add(attachment.id)
      evidenceItems.push({
        key: `log-${attachment.id}`,
        kind: 'LOG',
        status: attachment.available ? 'idle' : 'unavailable',
        attachment,
        log: diagnostic ?? {
          type: 'APPLICATION_LOG',
          source: 'GOVERNED_ATTACHMENT',
          attachmentId: attachment.id,
          fileSizeBytes: attachment.fileSizeBytes,
          mimeType: attachment.mimeType,
          available: attachment.available,
          securityStatus: attachment.securityStatus,
          integrityStatus: attachment.integrityStatus,
          malwareScanStatus: attachment.malwareScanStatus,
          contentReviewStatus: attachment.contentReviewStatus,
        },
      })
      continue
    }
    const looksLikeMedia = ['IMAGE', 'SCREENSHOT', 'ANNOTATED_SCREENSHOT', 'VIDEO', 'SCREEN_RECORDING'].includes(declaredType)
    evidenceItems.push({
      key: `attachment-${attachment.id}`,
      kind: mediaKind ?? 'OTHER',
      status: !attachment.available
        ? 'unavailable'
        : mediaKind && attachment.previewStatus === 'AVAILABLE'
          ? 'idle'
          : mediaKind && attachment.previewStatus !== 'FAILED'
            ? 'preview-pending'
            : mediaKind
              ? 'ready'
              : looksLikeMedia ? 'unsupported' : 'idle',
      attachment,
      error: mediaKind || !looksLikeMedia ? undefined : '附件格式不在安全预览白名单内',
    })
  }
  for (const log of props.logAttachments) {
    if (log.attachmentId != null && renderedLogAttachmentIds.has(log.attachmentId)) continue
    const attachment = log.attachmentId == null ? undefined : {
      id: log.attachmentId,
      attachmentType: 'LOG',
      mimeType: log.mimeType,
      fileSizeBytes: log.fileSizeBytes,
      securityStatus: log.securityStatus,
      integrityStatus: log.integrityStatus,
      malwareScanStatus: log.malwareScanStatus,
      contentReviewStatus: log.contentReviewStatus,
      available: log.available,
    }
    evidenceItems.push({
      key: `log-${log.attachmentId ?? log.sourceAttachmentId}`,
      kind: 'LOG',
      status: log.available && attachment ? 'idle' : 'unavailable',
      attachment,
      log,
    })
  }
  // 列表投影只提供日志数量，日志明细仍由点击后的受控诊断接口读取，避免列表逐行请求敏感信息。
  if (props.attachments === undefined && props.logAttachments.length === 0) {
    const loadedLogCount = evidenceItems.filter((item) => item.kind === 'LOG').length
    const logCount = Number.isFinite(props.counts.log)
      ? Math.max(0, Math.trunc(props.counts.log ?? 0) - loadedLogCount)
      : 0
    for (let index = 0; index < logCount; index += 1) {
      evidenceItems.push({
        key: `log-summary-${index}`,
        kind: 'LOG',
        status: 'ready',
        log: {
          type: 'APPLICATION_LOG',
          source: 'GOVERNED_ATTACHMENT',
          available: true,
        },
      })
    }
  }
  if (preserveLoaded) {
    const retained = new Set(evidenceItems.map(item => item.key))
    for (const item of evidenceItems) {
      const old = previous.get(item.key)
      if (!old) continue
      if (item.status === 'unavailable') {
        session.revokeObjectUrl(old.contentUrl)
        session.revokeObjectUrl(old.thumbnailUrl)
        continue
      }
      item.contentUrl = old.contentUrl
      item.thumbnailUrl = old.thumbnailUrl
      item.previewText = old.previewText
      item.previewRetryCount = old.previewRetryCount
      if (item.contentUrl || item.thumbnailUrl || item.previewText) item.status = 'ready'
    }
    for (const old of previous.values()) {
      if (retained.has(old.key)) continue
      session.revokeObjectUrl(old.contentUrl)
      session.revokeObjectUrl(old.thumbnailUrl)
    }
  }
  void nextTick(observeItems)
}

async function ensureSummaries(signal: AbortSignal) {
  if (summariesLoaded.value) return
  try {
    summaries.value = await getFeedbackAttachments(props.sourceId, signal)
    summariesLoaded.value = true
    summariesSkippedByZeroCount.value = false
    rebuildItems()
  } catch (error) {
    if (!signal.aborted) ElMessage.error(controlledEvidenceErrorMessage(error, '附件') || '附件摘要加载失败')
  }
}

function registerElement(key: string, element: Element | ComponentPublicInstance | null) {
  if (element instanceof Element) elements.set(key, element)
  else elements.delete(key)
}

function observeItems() {
  observer?.disconnect()
  observer = undefined
  if (typeof IntersectionObserver === 'undefined') {
    evidenceItems.filter(isAutomaticEvidence).forEach((item) => void loadAutomatic(item))
    return
  }
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue
      const key = (entry.target as HTMLElement).dataset.evidenceKey
      const item = evidenceItems.find((candidate) => candidate.key === key)
      if (item && isAutomaticEvidence(item)) void loadAutomatic(item)
      observer?.unobserve(entry.target)
    }
  }, { rootMargin: '120px' })
  elements.forEach((element, key) => {
    const item = evidenceItems.find((candidate) => candidate.key === key)
    if (item && isAutomaticEvidence(item)) observer?.observe(element)
  })
}

function isAutomaticEvidence(item: EvidenceItem) {
  // 日志与媒体缩略图都读取服务端预生成的小型派生内容，绝不自动打开原件。
  return ['IMAGE', 'VIDEO', 'LOG'].includes(item.kind) && item.attachment != null &&
    (item.status === 'idle' || (item.kind !== 'LOG' && item.status === 'preview-pending'))
}

async function loadAutomatic(item: EvidenceItem) {
  if (!isAutomaticEvidence(item)) return
  item.status = 'queued'
  const controller = session.createController()
  try {
    await withGovernedPreviewSlot(
      () => item.kind === 'LOG' ? ensureLogThumbnail(item, controller.signal) : ensureThumbnail(item, controller.signal),
      controller.signal,
    )
  } catch (error) {
    if (!controller.signal.aborted && item.kind === 'LOG' && isLogPreviewPending(error)) {
      scheduleLogPreviewRetry(item)
      return
    }
    if (!controller.signal.aborted && item.kind !== 'LOG' && isMediaPreviewPending(error)) {
      scheduleMediaPreviewRetry(item)
      return
    }
    // 内容加载器可能在等待期间判定格式不受支持；此处保留该状态，避免统一覆盖成普通加载失败。
    if (!controller.signal.aborted && !item.contentUrl && !item.previewText && !isUnsupported(item)) item.status = 'error'
  } finally {
    session.releaseController(controller)
  }
}

function isMediaPreviewPending(error: unknown) {
  return (error as { message?: string })?.message === 'ATTACHMENT_MEDIA_PREVIEW_PENDING'
}

function scheduleMediaPreviewRetry(item: EvidenceItem) {
  item.status = 'preview-pending'
  const attempt = (item.previewRetryCount ?? 0) + 1
  item.previewRetryCount = attempt
  if (attempt > 12 || previewRetryTimers.has(item.key)) return
  const delay = Math.min(15_000, 2_000 + attempt * 1_000)
  const timer = window.setTimeout(() => {
    previewRetryTimers.delete(item.key)
    if (item.status !== 'preview-pending') return
    void loadAutomatic(item)
  }, delay)
  previewRetryTimers.set(item.key, timer)
}

async function ensureLogThumbnail(item: EvidenceItem, signal: AbortSignal) {
  const attachment = item.attachment
  if (!attachment) throw new Error('ATTACHMENT_SUMMARY_MISSING')
  const requestGeneration = generation
  item.status = 'loading'
  const grant = await authorizeFeedbackAttachment(props.sourceId, attachment.id, signal)
  const blob = await streamAuthorizedFeedbackAttachment(grant.downloadUrl, {
    timeoutMs: 15_000,
    previewBytes: LOG_THUMBNAIL_BYTES,
    signal,
  })
  const parsed = await parseGovernedLogBlob(blob, { signal })
  if (signal.aborted || requestGeneration !== generation) throw new DOMException('aborted', 'AbortError')
  item.previewText = parsed.lines.slice(0, 6).join('\n').trim().slice(0, LOG_THUMBNAIL_CHARACTERS) || '日志无文本内容'
  item.previewRetryCount = 0
  item.status = 'ready'
}

function isLogPreviewPending(error: unknown) {
  return Number((error as { response?: { status?: number } })?.response?.status || 0) === 409
}

function scheduleLogPreviewRetry(item: EvidenceItem) {
  item.status = 'preview-pending'
  const attempt = (item.previewRetryCount ?? 0) + 1
  item.previewRetryCount = attempt
  if (attempt > 12 || previewRetryTimers.has(item.key)) return
  const delay = Math.min(15_000, 2_000 + attempt * 1_000)
  const timer = window.setTimeout(() => {
    previewRetryTimers.delete(item.key)
    if (item.status !== 'preview-pending') return
    item.status = 'idle'
    void loadAutomatic(item)
  }, delay)
  previewRetryTimers.set(item.key, timer)
}

function clearPreviewRetryTimers() {
  previewRetryTimers.forEach((timer) => window.clearTimeout(timer))
  previewRetryTimers.clear()
}

function logThumbnailText(item: EvidenceItem) {
  if (item.previewText) return item.previewText
  if (item.status === 'unavailable') return '日志暂不可用'
  if (item.status === 'error') return '日志加载失败'
  if (item.status === 'loading' || item.status === 'queued') return '正在加载日志…'
  if (item.status === 'preview-pending') return '日志预览生成中…'
  return '日志预览生成中…'
}

function isUnsupported(item: EvidenceItem) {
  return item.status === 'unsupported'
}

function ensureContent(item: EvidenceItem, inheritedSignal?: AbortSignal): Promise<Blob> {
  const existing = contentLoads.get(item.key)
  if (existing) return existing
  if (!item.attachment) return Promise.reject(new Error('ATTACHMENT_SUMMARY_MISSING'))
  const request = loadContentOnce(item, inheritedSignal)
  contentLoads.set(item.key, request)
  request.catch(() => contentLoads.delete(item.key))
  return request
}

async function loadContentOnce(item: EvidenceItem, inheritedSignal?: AbortSignal): Promise<Blob> {
  const attachment = item.attachment
  if (!attachment) throw new Error('ATTACHMENT_SUMMARY_MISSING')
  const requestGeneration = generation
  const ownController = inheritedSignal ? undefined : session.createController()
  const signal = inheritedSignal ?? ownController!.signal
  item.status = 'loading'
  item.error = undefined
  try {
    const grant = await authorizeFeedbackAttachment(props.sourceId, attachment.id, signal)
    const blob = await streamAuthorizedFeedbackAttachment(grant.downloadUrl, {
      timeoutMs: controlledDownloadTimeoutMs(attachment.fileSizeBytes),
      signal,
    })
    if (signal.aborted || requestGeneration !== generation) throw new DOMException('aborted', 'AbortError')
    if (!governedBlobMatches(attachment, blob)) {
      item.status = 'unsupported'
      item.error = '附件响应类型与安全元数据不一致'
      throw new Error('ATTACHMENT_RESPONSE_TYPE_MISMATCH')
    }
    item.contentUrl = session.createObjectUrl(blob)
    item.status = 'ready'
    return blob
  } catch (error) {
    if (!signal.aborted && requestGeneration === generation) {
      if (item.status !== 'unsupported') {
        item.status = 'error'
        item.error = controlledEvidenceErrorMessage(error, item.kind === 'VIDEO' ? '视频' : '截图') || undefined
      }
    }
    throw error
  } finally {
    if (ownController) session.releaseController(ownController)
  }
}

function ensureThumbnail(item: EvidenceItem, signal: AbortSignal): Promise<void> {
  if (item.thumbnailUrl) return Promise.resolve()
  const existing = thumbnailLoads.get(item.key)
  if (existing) return existing
  const request = (async () => {
    const requestGeneration = generation
    item.status = 'loading'
    const authorization = await requestGovernedPreviewAuthorization(props.sourceId, item.attachment!.id, signal)
    if (authorization.status === 'PENDING') throw new Error('ATTACHMENT_MEDIA_PREVIEW_PENDING')
    if (authorization.status !== 'AVAILABLE' || !authorization.grant) {
      item.status = 'unavailable'
      throw new Error('ATTACHMENT_MEDIA_PREVIEW_UNAVAILABLE')
    }
    const grant = authorization.grant
    const blob = await streamAuthorizedFeedbackAttachment(grant.downloadUrl, { timeoutMs: 15_000, signal })
    if (signal.aborted || requestGeneration !== generation) throw new DOMException('aborted', 'AbortError')
    if (blob.type !== 'image/jpeg') {
      item.status = 'unsupported'
      item.error = '派生预览响应类型不匹配'
      throw new Error('ATTACHMENT_PREVIEW_RESPONSE_TYPE_MISMATCH')
    }
    item.thumbnailUrl = session.createObjectUrl(blob)
    item.status = 'ready'
  })()
  thumbnailLoads.set(item.key, request)
  request.catch(() => thumbnailLoads.delete(item.key))
  return request
}

async function retryContent(item: EvidenceItem) {
  if (item.status === 'queued' || item.status === 'loading') return
  contentLoads.delete(item.key)
  thumbnailLoads.delete(item.key)
  session.revokeObjectUrl(item.contentUrl)
  session.revokeObjectUrl(item.thumbnailUrl)
  item.contentUrl = undefined
  item.thumbnailUrl = undefined
  item.status = 'idle'
  item.error = undefined
  try {
    await ensureContent(item)
  } catch {
    // 状态和稳定错误文案由唯一内容加载器设置。
  }
}

async function activate(item: EvidenceItem) {
  if (item.kind === 'LOG' && item.log) {
    emit('view-log', item.log)
    return
  }
  if (item.kind === 'OTHER' || item.status === 'unsupported' || item.status === 'unavailable') {
    ElMessage.warning(accessibleLabel(item))
    return
  }
  activeItem.value = item
  mediaDialogVisible.value = true
  if (!item.contentUrl) {
    try {
      await ensureContent(item)
    } catch {
      // 弹窗保持打开，并在失败后切换为可重试状态。
    }
  }
}

function closeDialog() {
  activeItem.value = undefined
}

function markUnsupported(item?: EvidenceItem) {
  if (!item) return
  item.status = 'unsupported'
  item.error = `${item.kind === 'VIDEO' ? '视频' : '图片'}编码不受当前浏览器支持`
}

function accessibleLabel(item: EvidenceItem) {
  const noun = item.kind === 'IMAGE' ? '图片' : item.kind === 'VIDEO' ? '视频' : item.kind === 'LOG' ? '日志' : '附件'
  if (item.status === 'loading' || item.status === 'queued') return `${noun}正在加载`
  if (item.status === 'preview-pending') return `${noun}预览生成中，点击可直接查看原件`
  if (item.status === 'unavailable') return `${noun}安全检查中、检查失败、文件缺失或当前不可用`
  if (item.status === 'unsupported') return item.error || `${noun}格式不支持页面预览`
  if (item.status === 'error') return item.error || `${noun}加载失败，点击重试`
  return item.kind === 'LOG' ? '下载日志' : `查看${noun}`
}

watch(
  [() => props.sourceId, () => props.attachments, () => props.logAttachments, () => props.counts],
  async ([nextSourceId, attachments], [previousSourceId, previousAttachments]) => {
    generation += 1
    observer?.disconnect()
    elements.clear()
    mediaDialogVisible.value = false
    activeItem.value = undefined

    const sourceChanged = nextSourceId !== previousSourceId
    const attachmentsChanged = attachments !== previousAttachments
    if (sourceChanged || attachmentsChanged) {
      summaries.value = []
    }

    if (attachments !== undefined) {
      summaries.value = [...attachments]
      summariesLoaded.value = true
      summariesSkippedByZeroCount.value = false
    } else if (hasExplicitZeroEvidenceCounts()) {
      summaries.value = []
      summariesLoaded.value = true
      summariesSkippedByZeroCount.value = true
    } else if (sourceChanged || attachmentsChanged || summariesSkippedByZeroCount.value) {
      summariesLoaded.value = false
      summariesSkippedByZeroCount.value = false
    }

    rebuildItems(!sourceChanged)
    if (!summariesLoaded.value) {
      const controller = session.createController()
      try {
        await ensureSummaries(controller.signal)
      } finally {
        session.releaseController(controller)
      }
    }
  },
  { deep: true },
)

onMounted(async () => {
  if (!summariesLoaded.value) {
    rebuildItems()
    const controller = session.createController()
    try {
      await ensureSummaries(controller.signal)
    } finally {
      session.releaseController(controller)
    }
  } else rebuildItems()
})
onBeforeUnmount(() => {
  generation += 1
  observer?.disconnect()
  elements.clear()
  contentLoads.clear()
  thumbnailLoads.clear()
  clearPreviewRetryTimers()
})
</script>

<style scoped>
.governed-evidence {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
}
.governed-evidence--compact {
  align-content: flex-start;
  flex-wrap: wrap;
  padding: 2px 0;
}
.evidence-thumbnail {
  position: relative;
  display: grid;
  flex: 0 0 88px;
  place-items: center;
  width: 88px;
  height: 62px;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  cursor: pointer;
}
.governed-evidence--compact .evidence-thumbnail {
  flex-basis: 72px;
  width: 72px;
  height: 50px;
  border-radius: 7px;
}
.evidence-thumbnail--log {
  place-items: stretch;
  width: 220px;
  height: 96px;
  flex-basis: 220px;
  border: 1px solid #344353;
  background: #17212d;
  color: #d9e2ec;
  text-align: left;
}
.governed-evidence--compact .evidence-thumbnail--log {
  width: 152px;
  height: 64px;
  flex-basis: 152px;
}
.evidence-thumbnail__log {
  display: -webkit-box;
  margin: 0;
  padding: 7px 8px;
  overflow: hidden;
  font: 10px/1.4 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}
.evidence-thumbnail:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
.evidence-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.evidence-thumbnail__state,
.evidence-thumbnail__file {
  font-size: 26px;
  line-height: 1;
}
.evidence-thumbnail.is-loading .evidence-thumbnail__state,
.evidence-thumbnail.is-queued .evidence-thumbnail__state {
  animation: spin 1s linear infinite;
}
.evidence-thumbnail.is-error,
.evidence-thumbnail.is-unavailable,
.evidence-thumbnail.is-unsupported {
  background: var(--el-fill-color);
  color: var(--el-text-color-placeholder);
}
.evidence-thumbnail__play {
  position: absolute;
  inset: 50% auto auto 50%;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: rgba(15, 31, 51, 0.78);
  color: #fff;
  font-size: 12px;
  transform: translate(-50%, -50%);
}
.media-dialog {
  display: grid;
  min-height: 240px;
  place-items: center;
  background: #101820;
}
.media-dialog__skeleton {
  width: 100%;
  min-height: 240px;
}
.media-dialog img,
.media-dialog video {
  display: block;
  max-width: 100%;
  max-height: 68vh;
}
.media-dialog video {
  width: 100%;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
