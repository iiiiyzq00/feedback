<template>
  <el-card class="channel-card" :class="channelClass" shadow="hover" @click="$emit('click')">
    <div class="channel-card-content">
      <div class="channel-summary">
        <div class="channel-icon">
          <el-icon :size="28">
            <component :is="iconMap[channel]" />
          </el-icon>
        </div>
        <div class="channel-heading">
          <div class="channel-title">
            <span class="health-dot" :class="status" />
            <span>{{ label }}</span>
          </div>
          <div class="channel-count">{{ formatNumber(count) }}</div>
          <div class="channel-count-label">累计接入数据</div>
        </div>
      </div>
      <div class="channel-details">
        <div class="detail-row">
          <span>最近入库</span>
          <strong>{{ lastTime ? formatTime(lastTime) : '—' }}</strong>
        </div>
        <div class="detail-row">
          <span>最新业务</span>
          <strong>{{ businessTime ? formatTime(businessTime) : '—' }}</strong>
        </div>
        <div class="channel-reason">
          <span class="reason-label">接入状态</span>
          <span>{{ statusReason || '等待健康检查' }}</span>
        </div>
      </div>
      <div class="quality-panel" @click.stop>
        <el-popover placement="bottom" :width="250" trigger="click">
          <template #reference>
            <button class="quality-metric" type="button">
              <span>内容质量</span>
              <strong :class="`is-${qualityLevel || 'none'}`">{{ qualityText }}</strong>
              <small>点击查看口径</small>
            </button>
          </template>
          <div>正式反馈样本：{{ qualitySampleCount || 0 }}</div>
          <div>脱敏通过率：{{ maskingPassRate == null ? '无样本' : `${(maskingPassRate * 100).toFixed(1)}%` }}</div>
          <div>统一失败数：{{ failureCount || 0 }}（脱敏 {{ maskingFailed || 0 }} + 最新批次失败行 {{ batchFailedRows || 0 }}；其中映射失败 {{ mappingFailed || 0 }}）</div>
          <div>字段变更待处理：{{ mappingFailed || 0 }}</div>
          <div>综合质量分：{{ qualityScore == null ? '无样本' : `${(qualityScore * 100).toFixed(1)}%` }}</div>
          <div v-if="latestBatch">最新批次：{{ latestBatch.batchCode }} · {{ latestBatch.batchStatus }} · 对账 {{ latestBatch.reconciliationStatus }}</div>
          <div class="metric-note">{{ qualityDefinitionText }}</div>
        </el-popover>
        <el-button v-if="suggestedAction" size="small" text type="warning" @click="$emit('action', suggestedAction.route)">{{ suggestedAction.label }}</el-button>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Tickets, SetUp, TrendCharts, Connection } from '@element-plus/icons-vue'
import LearningMachineIcon from './LearningMachineIcon.vue'

const props = defineProps<{
  channel: string
  label: string
  status: 'green' | 'yellow' | 'red'
  count: number
  lastTime?: string | null
  businessTime?: string | null
  statusReason?: string
  qualityScore?: number | null
  qualityLevel?: 'green'|'yellow'|'red'|'none'
  qualitySampleCount?: number
  qualityDefinition?: 'CURRENT_EFFECTIVE_CHANNEL'|'RECORD_WEIGHTED'|'IMPORT_BATCH_WEIGHTED'
  maskingPassRate?: number | null
  maskingFailed?: number
  batchFailedRows?: number
  failureCount?: number
  latestBatch?: Record<string, any> | null
  mappingFailed?: number
  suggestedAction?: { label:string; route:string } | null
}>()

defineEmits<{ (e: 'click'): void; (e:'action',route:string):void }>()

const iconMap: Record<string, any> = {
  sdk: LearningMachineIcon,
  station_feedback: Connection,
  jira: Tickets,
  quality: SetUp,
  redblack: TrendCharts,
}

const channelClass = computed(() => `channel-${props.channel}`)
const qualityText=computed(()=>props.qualityScore==null?'无样本':`${(props.qualityScore*100).toFixed(1)}%`)
const qualityDefinitionText=computed(()=>props.qualityDefinition==='CURRENT_EFFECTIVE_CHANNEL'
  ? '与“渠道数据质量”综合分完全同口径：当前有效数据中，必填完整25% + 时间有效10% + 枚举有效10% + 应用/模块/版本映射平均20% + 脱敏通过30% + 非跨渠道疑似重复5%。不包含接入新鲜度；重复率和隔离数单独参与阈值判定。'
  : props.qualityDefinition==='IMPORT_BATCH_WEIGHTED'
    ? '兼容口径：按历史终态批次有效行数加权；不包含接入新鲜度。'
    : '兼容口径：按正式反馈的单条质量分加权；不包含接入新鲜度。')

function formatNumber(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return String(n)
}
function pad(v:number){return String(v).padStart(2,'0')}
function formatTime(v:string):string{
  const d=new Date(v)
  if(Number.isNaN(d.getTime())) return v
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
</script>

<style scoped>
.channel-card {
  min-width: 0;
  height: 100%;
  min-height: 238px;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
}

.channel-card :deep(.el-card__body) {
  height: 100%;
  box-sizing: border-box;
}

.channel-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}

.channel-card-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 198px;
  min-width: 0;
}

.channel-summary {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  min-width: 0;
}

.channel-icon {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--bg-body);
}

.channel-heading { min-width: 0; }

.channel-sdk .channel-icon { color: var(--channel-sdk); }
.channel-jira .channel-icon { color: var(--channel-jira); }
.channel-quality .channel-icon { color: var(--channel-quality); }
.channel-redblack .channel-icon { color: var(--channel-redblack); }

.channel-title {
  display: flex;
  align-items: center;
  font-size: 15px;
  font-weight: 600;
}

.channel-count {
  font-size: 24px;
  font-weight: 700;
  margin-top: 4px;
  line-height: 1.1;
}

.channel-count-label {
  margin-top: 2px;
  color: var(--text-secondary);
  font-size: 11px;
}

.channel-details {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--bg-body);
}

.detail-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  align-items: baseline;
  color: var(--text-secondary);
  font-size: 11px;
}

.detail-row strong {
  color: var(--text-regular);
  font-size: 11px;
  font-weight: 500;
  text-align: right;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.channel-reason {
  display: flex;
  gap: 8px;
  padding-top: 7px;
  border-top: 1px solid var(--border-light);
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.4;
}

.reason-label { flex: 0 0 auto; }

.quality-panel {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-top: auto;
}

.quality-metric {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 9px 11px;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  background: #fff;
  color: var(--text-regular);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.quality-metric:hover { border-color: #9fc3f6; background: #f7faff; }
.quality-metric span { font-size: 12px; font-weight: 600; }
.quality-metric strong { font-size: 16px; text-align: right; font-variant-numeric: tabular-nums; }
.quality-metric strong.is-green { color: var(--color-success); }
.quality-metric strong.is-yellow { color: var(--color-warning); }
.quality-metric strong.is-red { color: var(--color-danger); }
.quality-metric small { color: var(--text-secondary); font-size: 10px; white-space: nowrap; }
.quality-panel :deep(.el-button){flex:0 0 auto;margin-left:0}
.metric-note{margin-top:8px;color:var(--text-secondary);font-size:12px;line-height:1.5}

@media (max-width: 1460px) and (min-width: 1281px) {
  .channel-card { min-height: 252px; }
  .quality-metric { grid-template-columns: auto 1fr; }
  .quality-metric small { grid-column: 1 / -1; }
}

@media (max-width: 520px) {
  .channel-card { min-height: 238px; }
  .quality-panel { flex-wrap: wrap; }
  .quality-panel :deep(.el-button) { width: 100%; }
}
</style>
