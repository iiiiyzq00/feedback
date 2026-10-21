<template>
  <section class="governed-log" aria-label="日志预览">
    <header class="governed-log__summary">
      <div>
        <strong>{{ visibilityLabel }}</strong>
        <span>服务端有界预览，上限 {{ formatPreviewBytes(limitBytes) }}</span>
      </div>
      <div class="governed-log__metrics">
        <el-tag :type="truncated ? 'warning' : 'success'" size="small">
          {{ truncated ? '已截断' : '完整预览' }}
        </el-tag>
        <span>已接收 {{ formatPreviewBytes(receivedBytes) }}</span>
        <span v-if="lines.length">显示第 {{ visibleStart + 1 }}–{{ visibleEnd }} 行 / 共 {{ lines.length }} 行</span>
      </div>
    </header>

    <div
      ref="viewport"
      class="governed-log__viewport"
      tabindex="0"
      @scroll="handleScroll"
    >
      <div v-if="lines.length" class="governed-log__canvas" :style="{ height: `${canvasHeight}px` }">
        <div
          v-for="row in visibleRows"
          :key="row.index"
          class="governed-log__line"
          :style="{ transform: `translateY(${row.index * lineHeight}px)` }"
        >
          <span class="governed-log__number">{{ row.index + 1 }}</span>
          <code>{{ row.value || ' ' }}</code>
        </div>
      </div>
      <el-empty v-else description="日志中没有可显示的文本" :image-size="56" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { formatPreviewBytes } from '@/utils/governedLogPreview'

const props = defineProps<{
  lines: string[]
  receivedBytes: number
  truncated: boolean
  visibility: 'RAW' | 'PARTIALLY_HIDDEN'
  limitBytes?: number
}>()

const limitBytes = computed(() => props.limitBytes ?? 5 * 1024 * 1024)

const lineHeight = 22
const overscan = 12
const viewport = ref<HTMLElement>()
const scrollTop = ref(0)
const viewportHeight = ref(440)

const visibilityLabel = computed(() => props.visibility === 'RAW'
  ? '日志内容（原文）'
  : '日志内容（敏感片段已隐藏）')
const visibleStart = computed(() => Math.max(0, Math.floor(scrollTop.value / lineHeight) - overscan))
const visibleCount = computed(() => Math.ceil(viewportHeight.value / lineHeight) + overscan * 2)
const visibleEnd = computed(() => Math.min(props.lines.length, visibleStart.value + visibleCount.value))
const visibleRows = computed(() => props.lines.slice(visibleStart.value, visibleEnd.value)
  .map((value, offset) => ({ index: visibleStart.value + offset, value })))
const canvasHeight = computed(() => props.lines.length * lineHeight)

function handleScroll(event: Event) {
  const element = event.currentTarget as HTMLElement
  scrollTop.value = element.scrollTop
  viewportHeight.value = element.clientHeight || viewportHeight.value
}

watch(() => props.lines, async () => {
  scrollTop.value = 0
  await nextTick()
  if (viewport.value) {
    viewport.value.scrollTop = 0
    viewportHeight.value = viewport.value.clientHeight || viewportHeight.value
  }
})
</script>

<style scoped>
.governed-log{display:grid;gap:12px;min-width:0}.governed-log__summary{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding:12px 14px;border:1px solid var(--el-border-color-lighter);border-radius:10px;background:var(--el-fill-color-lighter)}.governed-log__summary>div{display:grid;gap:4px}.governed-log__summary strong{color:var(--el-text-color-primary);font-size:13px}.governed-log__summary span{color:var(--el-text-color-secondary);font-size:12px}.governed-log__metrics{justify-items:end}.governed-log__viewport{position:relative;height:min(56vh,560px);min-height:260px;overflow:auto;border:1px solid #d8e1ec;border-radius:10px;background:#17212d;color:#d9e2ec;outline:none}.governed-log__viewport:focus{box-shadow:0 0 0 2px var(--el-color-primary-light-7)}.governed-log__canvas{position:relative;min-width:max-content}.governed-log__line{position:absolute;top:0;left:0;display:grid;grid-template-columns:72px minmax(max-content,1fr);width:100%;height:22px;font:12px/22px ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;white-space:pre}.governed-log__number{position:sticky;left:0;z-index:1;padding-right:12px;border-right:1px solid #344353;background:#1e2a37;color:#7f93a8;text-align:right;user-select:none}.governed-log__line code{padding:0 12px;color:inherit;font:inherit}.governed-log__viewport :deep(.el-empty__description p){color:#9aabba}@media(max-width:640px){.governed-log__summary{align-items:stretch;flex-direction:column}.governed-log__metrics{justify-items:start}.governed-log__viewport{height:58vh}.governed-log__line{grid-template-columns:52px minmax(max-content,1fr)}}
</style>
