<template>
  <el-dialog
    v-model="visible"
    title="关键信息与日志"
    width="min(720px, calc(100vw - 24px))"
    destroy-on-close
    @closed="clear"
  >
    <div v-loading="loading" element-loading-text="正在读取信息" class="diagnostics-dialog">
      <el-alert
        :title="visibility === 'RAW' ? '当前展示日志原文' : '当前日志中的敏感片段已隐藏'"
        type="info"
        :closable="false"
      />
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />

      <div v-if="diagnostics.length" class="diagnostic-results">
        <article v-for="(item, index) in diagnostics" :key="diagnosticKey(item, index)" class="diagnostic-card">
          <div class="diagnostic-card__heading">
            <strong>{{ diagnosticTypeLabel(item.type) }}</strong>
            <el-tag
              v-if="item.source === 'GOVERNED_ATTACHMENT'"
              :type="item.available ? 'success' : 'warning'"
              size="small"
            >
              {{ item.available ? '可直接查看' : '安全检查中' }}
            </el-tag>
          </div>
          <p>{{ diagnosticPreview(item) }}</p>
          <el-button
            v-if="isDetailedDiagnostic(item.type)"
            text
            type="primary"
            :loading="isOpening(item)"
            :disabled="openingKey !== undefined && !isOpening(item)"
            @click="openDetail(item)"
          >
            {{ detailActionLabel(item) }}
          </el-button>
        </article>
      </div>
      <el-empty v-else-if="!loading && !loadError" description="当前无可查看的关键信息或日志" />
    </div>
  </el-dialog>

  <el-dialog
    v-model="detailVisible"
    width="min(920px, calc(100vw - 24px))"
    destroy-on-close
    @closed="clearDetail"
  >
    <template #header>
      <div class="diagnostic-detail__header">
        <span>{{ `${diagnosticTypeLabel(detailType)} · 预览` }}</span>
        <el-button
          v-if="detailItem?.source === 'GOVERNED_ATTACHMENT'"
          type="primary"
          plain
          :loading="downloadLoading"
          aria-label="下载日志"
          title="下载日志"
          @click="downloadLog"
        >下载日志</el-button>
      </div>
    </template>
    <div v-loading="detailLoading" :element-loading-text="detailLoadingText" class="diagnostic-detail">
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
      <GovernedLogPreview
        v-else-if="detailReady"
        :lines="detailLines"
        :received-bytes="detailReceivedBytes"
        :truncated="detailTruncated"
        :visibility="visibility"
        :limit-bytes="LOG_DETAIL_PREVIEW_BYTES"
      />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import GovernedLogPreview from './GovernedLogPreview.vue'
import {
  authorizeFeedbackAttachment,
  authorizeOriginalLogDownload,
  streamAuthorizedFeedbackAttachment,
  viewFeedbackDiagnostics,
  type FeedbackContentVisibility,
  type FeedbackDiagnosticItem,
  type FeedbackDiagnosticsResponse,
} from '@/api/feedback'
import type { GovernedDiagnosticsDialogExpose } from './GovernedDiagnosticsDialog.types'
import { useGovernedAttachmentSession } from '@/composables/useGovernedAttachmentSession'
import {
  controlledAttachmentFilename,
  controlledDownloadErrorMessage,
  controlledDownloadProgress,
  controlledDownloadStatusText,
  controlledDownloadTimeoutMs,
  saveControlledBlob,
  type ControlledDownloadStage,
} from '@/utils/controlledAttachmentDownload'
import { parseGovernedLogBlob } from '@/utils/governedLogPreview'

const detailedDiagnosticTypes = new Set(['APPLICATION_LOG', 'ACCOUNT_CONTEXT', 'PRIVATE_FILE_MANIFEST'])
const LOG_DETAIL_PREVIEW_BYTES = 512 * 1024
const typeLabels: Record<string, string> = {
  PHONE: '手机号',
  EMAIL: '邮箱',
  DEVICE_SN: '设备序列号',
  USER_ID: '用户 ID',
  AUTHENTICATED_USER_ID: '认证用户 ID',
  APPLICATION_LOG: '应用日志',
  ACCOUNT_CONTEXT: '账号上下文',
  PRIVATE_FILE_MANIFEST: '私有文件清单',
}

const session = useGovernedAttachmentSession()
const visible = ref(false)
const loading = ref(false)
const loadError = ref('')
const diagnostics = ref<FeedbackDiagnosticItem[]>([])
const visibility = ref<FeedbackContentVisibility>('PARTIALLY_HIDDEN')
const sourceId = ref<number>()
const generation = ref(0)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detailType = ref('')
const detailLines = ref<string[]>([])
const detailReceivedBytes = ref(0)
const detailTruncated = ref(false)
const detailItem = ref<FeedbackDiagnosticItem>()
const downloadLoading = ref(false)
const openingKey = ref<string>()
const downloadStage = ref<ControlledDownloadStage>('authorizing')
const downloadProgress = ref<number | null>(null)
const downloadedBytes = ref(0)
const standaloneDetail = ref(false)
let directOpeningKey: string | undefined
let directOpeningPromise: Promise<void> | undefined

const detailReady = computed(() => !detailLoading.value && !detailError.value && detailLines.value.length > 0)
const detailLoadingText = computed(() => controlledDownloadStatusText(
  downloadStage.value,
  downloadedBytes.value,
  downloadProgress.value,
))

function diagnosticTypeLabel(type: string) {
  return typeLabels[type] || type
}

function isDetailedDiagnostic(type: string) {
  return detailedDiagnosticTypes.has(type)
}

function diagnosticPreview(item: FeedbackDiagnosticItem) {
  const value = String(item.value ?? '')
  return isDetailedDiagnostic(item.type) && value.length > 160 ? `${value.slice(0, 160)}…` : value
}

function itemKey(item: FeedbackDiagnosticItem) {
  return item.attachmentId != null ? `attachment-${item.attachmentId}` : `type-${item.type}`
}

function diagnosticKey(item: FeedbackDiagnosticItem, index: number) {
  return `${itemKey(item)}-${index}`
}

function isOpening(item: FeedbackDiagnosticItem) {
  return openingKey.value === itemKey(item)
}

function detailActionLabel(item: FeedbackDiagnosticItem) {
  if (isOpening(item)) return detailLoadingText.value
  if (item.source === 'GOVERNED_ATTACHMENT') return '下载日志'
  return '查看完整信息'
}

function resetDetailState() {
  detailLoading.value = false
  detailError.value = ''
  detailType.value = ''
  detailLines.value = []
  detailReceivedBytes.value = 0
  detailTruncated.value = false
  detailItem.value = undefined
  downloadLoading.value = false
  openingKey.value = undefined
  downloadStage.value = 'authorizing'
  downloadProgress.value = null
  downloadedBytes.value = 0
}

function clearDetail() {
  // 关闭详情同时递增代次并中止网络/解析；迟到回调即使已排队也不能重新写回敏感状态。
  generation.value += 1
  session.cancelAll()
  detailVisible.value = false
  resetDetailState()
  if (standaloneDetail.value) {
    diagnostics.value = []
    visibility.value = 'PARTIALLY_HIDDEN'
    sourceId.value = undefined
    standaloneDetail.value = false
  }
}

function clear() {
  // 主弹窗关闭必须清除全部诊断值、日志行、进度和来源 ID，避免下次打开短暂显示上一条反馈。
  generation.value += 1
  session.cancelAll()
  visible.value = false
  loading.value = false
  loadError.value = ''
  diagnostics.value = []
  visibility.value = 'PARTIALLY_HIDDEN'
  sourceId.value = undefined
  detailVisible.value = false
  standaloneDetail.value = false
  resetDetailState()
}

async function open(nextSourceId: number, initial?: FeedbackDiagnosticsResponse) {
  clear()
  // Abort 负责尽快停止工作，generation 负责隔离无法及时取消或已经完成的过期响应，两道门禁缺一不可。
  const requestGeneration = generation.value
  sourceId.value = nextSourceId
  visible.value = true
  if (initial) {
    diagnostics.value = initial.diagnostics ?? []
    visibility.value = initial.contentVisibility
    return
  }
  loading.value = true
  const controller = session.createController()
  try {
    const result = await viewFeedbackDiagnostics(nextSourceId, controller.signal)
    if (controller.signal.aborted || requestGeneration !== generation.value) return
    diagnostics.value = result.diagnostics ?? []
    visibility.value = result.contentVisibility
  } catch (error) {
    if (!controller.signal.aborted && requestGeneration === generation.value) {
      loadError.value = controlledDownloadErrorMessage(error) ?? ''
    }
  } finally {
    session.releaseController(controller)
    if (requestGeneration === generation.value) loading.value = false
  }
}

async function openDetail(item: FeedbackDiagnosticItem) {
  if (openingKey.value || sourceId.value == null) return
  if (item.source === 'GOVERNED_ATTACHMENT' && item.type === 'APPLICATION_LOG') {
    openingKey.value = itemKey(item)
    try {
      await downloadLogItem(item)
    } finally {
      openingKey.value = undefined
    }
    return
  }
  resetDetailState()
  detailType.value = item.type
  detailItem.value = item
  detailVisible.value = true

  if (item.source !== 'GOVERNED_ATTACHMENT') {
    // 非日志诊断值已由服务端完成角色展示策略，不再发起附件授权，也不在浏览器持久化。
    const value = String(item.value ?? '')
    detailLines.value = value.split(/\r?\n/)
    detailReceivedBytes.value = new TextEncoder().encode(value).byteLength
    return
  }

  if (!item.available || item.attachmentId == null) {
    detailError.value = unavailableDiagnosticMessage(item)
    return
  }

  const requestGeneration = generation.value
  const key = itemKey(item)
  openingKey.value = key
  detailLoading.value = true
  const controller = session.createController()
  try {
    // 页面无需填写理由；服务端按当前角色和实时安全状态决定是否返回一次性内容流。
    const grant = await authorizeFeedbackAttachment(sourceId.value, item.attachmentId, controller.signal)
    if (controller.signal.aborted || requestGeneration !== generation.value) return

    downloadStage.value = 'streaming'
    const blob = await streamAuthorizedFeedbackAttachment(grant.downloadUrl, {
      timeoutMs: controlledDownloadTimeoutMs(item.fileSizeBytes),
      previewBytes: LOG_DETAIL_PREVIEW_BYTES,
      signal: controller.signal,
      onProgress: (loaded, total) => {
        if (controller.signal.aborted || requestGeneration !== generation.value) return
        downloadedBytes.value = loaded
        downloadProgress.value = controlledDownloadProgress(loaded, total)
      },
    })
    if (controller.signal.aborted || requestGeneration !== generation.value) return

    downloadStage.value = 'parsing'
    const parsed = await parseGovernedLogBlob(blob, {
      signal: controller.signal,
      onProgress: (received) => { detailReceivedBytes.value = received },
    })
    if (controller.signal.aborted || requestGeneration !== generation.value) return
    detailLines.value = parsed.lines
    detailReceivedBytes.value = parsed.receivedBytes
    detailTruncated.value = parsed.truncated
  } catch (error) {
    if (!controller.signal.aborted && requestGeneration === generation.value) {
      detailError.value = controlledDownloadErrorMessage(error) ?? ''
    }
  } finally {
    session.releaseController(controller)
    if (requestGeneration === generation.value) {
      detailLoading.value = false
      openingKey.value = undefined
    }
  }
}

/** 详情页使用已取得的日志明细直接打开，不重复请求诊断接口。 */
async function openLog(nextSourceId: number, item: FeedbackDiagnosticItem, initial: FeedbackDiagnosticsResponse) {
  clear()
  sourceId.value = nextSourceId
  diagnostics.value = initial.diagnostics ?? []
  visibility.value = initial.contentVisibility
  await downloadLogItem(item)
  sourceId.value = undefined
  diagnostics.value = []
}

async function downloadLogItem(item: FeedbackDiagnosticItem) {
  if (sourceId.value == null || item.attachmentId == null) return
  const controller = session.createController()
  try {
    if (visibility.value === 'RAW') {
      const grant = await authorizeOriginalLogDownload(sourceId.value, item.attachmentId, controller.signal)
      const blob = await streamAuthorizedFeedbackAttachment(grant.downloadUrl, {
        timeoutMs: 0,
        signal: controller.signal,
      })
      if (controller.signal.aborted) return
      saveControlledBlob(blob, controlledAttachmentFilename(item, 'original-log'))
      ElMessage.success('日志下载已开始')
      return
    }
    const grant = await authorizeFeedbackAttachment(sourceId.value, item.attachmentId, controller.signal)
    const blob = await streamAuthorizedFeedbackAttachment(grant.downloadUrl, {
      timeoutMs: controlledDownloadTimeoutMs(item.fileSizeBytes),
      previewBytes: LOG_DETAIL_PREVIEW_BYTES,
      signal: controller.signal,
    })
    if (controller.signal.aborted) return
    saveControlledBlob(
      blob,
      controlledAttachmentFilename({ ...item, mimeType: 'text/plain' }, 'log-preview'),
    )
    ElMessage.success('日志预览下载已开始')
  } catch (error) {
    const message = controlledDownloadErrorMessage(error)
    if (message) ElMessage.error(message.replace('查看', '下载'))
  } finally {
    session.releaseController(controller)
  }
}

/** 列表点击日志缩略内容后，读取当前日志展示级别并直接打开对应附件。 */
async function openLogDirect(nextSourceId: number, item: FeedbackDiagnosticItem) {
  const key = `${nextSourceId}:${itemKey(item)}`
  if (directOpeningPromise && directOpeningKey === key) return directOpeningPromise

  const operation = (async () => {
    clear()
    const requestGeneration = generation.value
    sourceId.value = nextSourceId
    standaloneDetail.value = true
    const controller = session.createController()
    try {
      const result = await viewFeedbackDiagnostics(nextSourceId, controller.signal)
      if (controller.signal.aborted || requestGeneration !== generation.value) return
      diagnostics.value = result.diagnostics ?? []
      visibility.value = result.contentVisibility
      const matched = diagnostics.value.find((candidate) => candidate.attachmentId === item.attachmentId)
      await downloadLogItem(matched ?? item)
      sourceId.value = undefined
      diagnostics.value = []
    } catch (error) {
      if (!controller.signal.aborted && requestGeneration === generation.value) {
        const message = controlledDownloadErrorMessage(error)
        if (message) ElMessage.error(message)
        standaloneDetail.value = false
        sourceId.value = undefined
      }
    } finally {
      session.releaseController(controller)
    }
  })()
  directOpeningKey = key
  directOpeningPromise = operation
  try {
    await operation
  } finally {
    if (directOpeningPromise === operation) {
      directOpeningPromise = undefined
      directOpeningKey = undefined
    }
  }
}

/** 技术角色下载原文件；产品角色下载当前已局部隐藏的文本预览。 */
async function downloadLog() {
  const item = detailItem.value
  if (sourceId.value == null || item?.attachmentId == null || downloadLoading.value) return
  downloadLoading.value = true
  if (visibility.value !== 'RAW') {
    const preview = new Blob([detailLines.value.join('\n')], { type: 'text/plain;charset=UTF-8' })
    saveControlledBlob(preview, controlledAttachmentFilename(item, 'log-preview'))
    ElMessage.success('日志预览下载已开始')
    downloadLoading.value = false
    return
  }
  const controller = session.createController()
  try {
    const grant = await authorizeOriginalLogDownload(
      sourceId.value,
      item.attachmentId,
      controller.signal,
    )
    const blob = await streamAuthorizedFeedbackAttachment(grant.downloadUrl, {
      timeoutMs: 0,
      signal: controller.signal,
    })
    if (controller.signal.aborted) return
    saveControlledBlob(blob, controlledAttachmentFilename(item, 'original-log'))
    ElMessage.success('原始日志下载已开始')
  } catch (error) {
    const message = controlledDownloadErrorMessage(error)
    if (message) ElMessage.error(message.replace('查看', '下载'))
  } finally {
    session.releaseController(controller)
    downloadLoading.value = false
  }
}

function unavailableDiagnosticMessage(item: FeedbackDiagnosticItem) {
  const statuses = [
    item.securityStatus,
    item.integrityStatus,
    item.malwareScanStatus,
    item.contentReviewStatus,
  ].map((value) => String(value || '').toUpperCase())
  if (statuses.some((value) => ['REJECTED', 'FAILED', 'INFECTED', 'BLOCKED'].includes(value))) {
    return '日志安全检查未通过，当前不允许查看'
  }
  if (statuses.some((value) => ['PENDING', 'PROCESSING', 'UNKNOWN', ''].includes(value))) {
    return '日志仍在安全检查中，暂不可查看'
  }
  return '日志文件缺失、已清理或当前不可用'
}

defineExpose<GovernedDiagnosticsDialogExpose>({ open, openLog, openLogDirect, clear })
</script>

<style scoped>
.diagnostic-detail__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.diagnostics-dialog {
  min-height: 160px;
}
.diagnostic-results {
  display: grid;
  gap: 12px;
  margin-top: 14px;
}
.diagnostic-card {
  display: grid;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}
.diagnostic-card__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.diagnostic-card p {
  margin: 0;
  color: var(--el-text-color-regular);
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.diagnostic-card :deep(.el-button) {
  justify-self: end;
  margin: 0;
}
.diagnostic-detail {
  min-height: 280px;
}
@media (max-width: 640px) {
  .diagnostic-card__heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .diagnostic-card :deep(.el-button) {
    justify-self: start;
  }
}
</style>
