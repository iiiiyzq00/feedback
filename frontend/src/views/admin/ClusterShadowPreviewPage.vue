<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>问题深度分析</h2>
        <p>按工程对象与故障表现归簇，优先展示反馈量最大的具体问题</p>
      </div>
      <div class="preview-meta">
        <el-tag :type="mode === 'primary' ? 'success' : 'warning'" effect="plain">
          {{ mode === 'primary' ? 'VERIFIED 新版分析' : 'Shadow 只读预览' }}
        </el-tag>
        <span>更新于 {{ formatTime(generatedAt) }}</span>
      </div>
    </div>

    <el-alert v-if="loadError" class="analysis-error" type="error" :closable="false" show-icon title="问题分析结果加载失败">
      <template #default>
        <span>当前预览严格绑定指定 VERIFIED generation，不会回退到旧数据。</span>
        <el-button text type="primary" :loading="loading" @click="doSearch()">重新加载</el-button>
      </template>
    </el-alert>

    <div class="overview-grid" aria-label="问题分类总览">
      <button
        v-for="section in analysisSections"
        :key="section.key"
        type="button"
        class="overview-card"
        :class="{ active: activeSection === section.key }"
        @click="selectSection(section.key)"
      >
        <span>{{ section.label }}</span>
        <b>{{ metric(section.recordCount) }}</b>
        <small>{{ section.clusterCount == null ? '按内容性质分类' : `${metric(section.clusterCount)} 个具体问题` }}</small>
        <p>{{ section.explanation }}</p>
      </button>
    </div>

    <el-card v-if="isClusterSection" shadow="never" class="analysis-card">
      <template #header>
        <div class="section-header">
          <div>
            <h3>{{ activeSectionInfo?.label }}</h3>
            <p>{{ activeSectionInfo?.explanation }}</p>
          </div>
          <span>共 {{ metric(clusters.total) }} 个具体问题</span>
        </div>
      </template>

      <div class="filter-bar">
        <el-checkbox v-model="filters.largeOnly" @change="analyze">只看高频问题（50+）</el-checkbox>
        <el-checkbox v-model="filters.crossChannelOnly" @change="analyze">只看跨渠道问题</el-checkbox>
        <el-checkbox v-model="filters.provisionalOnly" @change="analyze">包含待复核成员</el-checkbox>
        <el-button @click="resetFilters">重置筛选</el-button>
      </div>

      <el-table :data="clusters.items" v-loading="loading" empty-text="当前筛选条件下没有具体问题" row-class-name="problem-row">
        <el-table-column label="具体问题" min-width="390">
          <template #default="{ row }">
            <button class="problem-link" type="button" @click="openDetail(row.previewId)">
              <strong>{{ displayProblem(row) }}</strong>
              <span>{{ row.phenomenon || '故障表现待补充' }}</span>
            </button>
          </template>
        </el-table-column>
        <el-table-column prop="memberCount" label="反馈数量" width="105" sortable />
        <el-table-column label="反馈渠道" min-width="220">
          <template #default="{ row }">
            <span v-for="item in row.channels" :key="item.channel" class="channel-chip">
              {{ channelName(item.channel) }} <b>{{ item.count }}</b>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="问题状态" min-width="160">
          <template #default="{ row }">
            <el-tag v-if="row.large" type="warning">高频</el-tag>
            <el-tag v-if="row.crossChannel" type="success">跨渠道</el-tag>
            <el-tag v-if="row.provisional" type="info">部分待复核</el-tag>
            <span v-if="!row.large && !row.crossChannel && !row.provisional">已形成候选簇</span>
          </template>
        </el-table-column>
        <el-table-column label="工程线索" min-width="210">
          <template #default="{ row }">{{ engineeringClues(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="92" fixed="right">
          <template #default="{ row }"><el-button text type="primary" @click="openDetail(row.previewId)">查看详情</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination class="cluster-pagination" layout="total, prev, pager, next" :total="clusters.total" :page-size="clusters.size" :current-page="clusters.page" @current-change="changePage" />
    </el-card>

    <el-card v-else shadow="never" class="analysis-card">
      <template #header>
        <div class="section-header">
          <div><h3>{{ activeSectionInfo?.label }}</h3><p>{{ activeSectionInfo?.explanation }}</p></div>
          <span>共 {{ metric(activeSectionInfo?.recordCount) }} 条反馈</span>
        </div>
      </template>
      <div class="classification-grid">
        <div v-for="reason in visibleTerminalReasons" :key="reason.status" class="classification-card">
          <div><strong>{{ reason.label }}</strong><b>{{ metric(reason.count) }}</b></div>
          <p>{{ reason.explanation }}</p>
          <small>{{ percent(reason.count, activeSectionInfo?.recordCount || 0) }}</small>
        </div>
      </div>
      <el-alert type="info" :closable="false" show-icon title="这些内容尚未伪造成具体问题簇">
        当前 VERIFIED generation 只提供安全分类统计；硬件对象、系统能力和复合问题需要新的匿名投影通过审计后才能下钻。
      </el-alert>
    </el-card>

    <el-drawer v-model="detailVisible" :title="detail?.cluster.problem || '问题安全详情'" size="68%" destroy-on-close>
      <div v-if="detail" class="detail-summary">
        <div><span>反馈数量</span><b>{{ metric(detail.total) }}</b></div>
        <div><span>渠道</span><b>{{ detail.cluster.channels.map(item => channelName(item.channel)).join('、') }}</b></div>
        <div><span>状态</span><b>{{ detail.cluster.provisional ? '部分成员待复核' : '候选簇已形成' }}</b></div>
      </div>
      <el-alert type="info" :closable="false" show-icon title="仅展示安全匿名投影，不展示正文、来源 ID、业务 ID 或哈希。" />
      <el-table v-if="detail" :data="detail.members" v-loading="detailLoading">
        <el-table-column prop="anonymousId" label="匿名序号" width="150" />
        <el-table-column label="渠道" width="140"><template #default="{ row }">{{ channelName(row.channel) }}</template></el-table-column>
        <el-table-column label="成员状态" min-width="180"><template #default="{ row }">{{ decisionName(row.decision) }}</template></el-table-column>
        <el-table-column label="可用工程线索" min-width="260"><template #default="{ row }">{{ knownText(row.boundaryKnown) }}</template></el-table-column>
      </el-table>
      <el-pagination v-if="detail" class="cluster-pagination" layout="total, prev, pager, next" :total="detail.total" :page-size="detailSize" :current-page="detail.page" @current-change="changeDetailPage" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getShadowClusterDetail, getShadowPreview } from '@/api/clusterShadowPreview'
import type { ShadowAnalysisSection, ShadowClusterDetail, ShadowClusterItem, ShadowGeneration } from '@/api/clusterShadowPreview'

withDefaults(defineProps<{ mode?: 'preview' | 'primary' }>(), { mode: 'preview' })

type SectionKey = ShadowAnalysisSection['key']
type PreviewPage = { items: ShadowClusterItem[]; total: number; page: number; size: number }
const clusterSize = 20
const detailSize = 50
const clusters = reactive<PreviewPage>({ items: [], total: 0, page: 1, size: clusterSize })
const generation = ref<ShadowGeneration | null>(null)
const analysisSections = ref<ShadowAnalysisSection[]>([])
const activeSection = ref<SectionKey>('CONFIRMED_APPLICATION')
const generatedAt = ref<string | null>(null)
const loading = ref(false)
const loadError = ref(false)
const detailLoading = ref(false)
const detail = ref<ShadowClusterDetail | null>(null)
const detailVisible = ref(false)
const filters = reactive({ largeOnly: false, provisionalOnly: false, crossChannelOnly: false })
let searchSequence = 0

const activeSectionInfo = computed(() => analysisSections.value.find(section => section.key === activeSection.value))
const isClusterSection = computed(() => activeSection.value === 'CONFIRMED_APPLICATION' || activeSection.value === 'PENDING_APPLICATION')
const engineeringStatuses = new Set(['NON_FAULT_INPUT', 'USER_INTENT_INCOMPLETE', 'NOVEL_SYMPTOM', 'MULTI_PROBLEM_AMBIGUOUS'])
const unusableStatuses = new Set(['NOISE', 'LEGACY_INFORMATION_LOSS', 'INSUFFICIENT_INFORMATION'])
const visibleTerminalReasons = computed(() => (generation.value?.terminalReasons || []).filter(reason =>
  (activeSection.value === 'ENGINEERING_CLASSIFICATION' ? engineeringStatuses : unusableStatuses).has(reason.status)
))

async function doSearch() {
  const sequence = ++searchSequence
  loading.value = true
  loadError.value = false
  try {
    const response = await getShadowPreview({
      page: clusters.page,
      size: clusterSize,
      largeOnly: filters.largeOnly,
      provisionalOnly: filters.provisionalOnly,
      crossChannelOnly: filters.crossChannelOnly,
      analysisSection: activeSection.value === 'PENDING_APPLICATION' ? 'PENDING_APPLICATION' : 'CONFIRMED_APPLICATION',
    })
    if (sequence !== searchSequence) return
    Object.assign(clusters, { items: response.items, total: response.total, page: response.page, size: response.size })
    generation.value = response.generation
    analysisSections.value = response.analysisSections
    generatedAt.value = response.generatedAt
  } catch {
    if (sequence === searchSequence) { loadError.value = true; ElMessage.error('问题分析结果加载失败') }
  } finally { if (sequence === searchSequence) loading.value = false }
}
function selectSection(key: SectionKey) { activeSection.value = key; clusters.page = 1; if (isClusterSection.value) doSearch() }
function analyze() { clusters.page = 1; doSearch() }
function resetFilters() { Object.assign(filters, { largeOnly: false, provisionalOnly: false, crossChannelOnly: false }); analyze() }
function changePage(page: number) { clusters.page = page; doSearch() }
async function openDetail(previewId: string, page = 1) {
  detailVisible.value = true
  detailLoading.value = true
  try { detail.value = await getShadowClusterDetail(previewId, page, detailSize) }
  catch { ElMessage.error('问题安全详情加载失败') }
  finally { detailLoading.value = false }
}
function changeDetailPage(page: number) { if (detail.value) openDetail(detail.value.cluster.previewId, page) }
function metric(value: number | undefined) { return value == null ? '-' : value.toLocaleString('zh-CN') }
function percent(value: number, total: number) { return total > 0 ? `${(value * 100 / total).toFixed(1)}%` : '-' }
function formatTime(value: unknown) { if (!value) return '-'; const date = new Date(String(value)); return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN') }
function channelName(value: string) { return ({ STATION_FEEDBACK: '学习机', JIRA: 'Jira', QUALITY: '质量平台', REDBLACK: '红黑榜' } as Record<string, string>)[value] ?? value }
function displayProblem(row: ShadowClusterItem) {
  if (row.problem) return row.problem
  if (row.application && row.phenomenon) return `${row.application}·${row.phenomenon}`
  return row.phenomenon || '问题名称待安全重建'
}
function engineeringClues(row: ShadowClusterItem) {
  const clues = [['故障表现', row.boundaries.failureMode], ['对象', row.boundaries.target], ['场景动作', row.boundaries.action], ['错误特征', row.boundaries.errorSignature]]
    .filter(([, count]) => Number(count) > 0).map(([label]) => label)
  return clues.length ? clues.join('、') : '仅有规范化症状'
}
function knownText(value: Record<string, boolean>) { return Object.entries(value).filter(([, known]) => known).map(([key]) => ({ application: '应用', failureMode: '故障表现', target: '对象', action: '场景动作', errorSignature: '错误特征' } as Record<string, string>)[key]).join('、') || '仅有规范化症状' }
function decisionName(value: string) { return value.startsWith('PROVISIONAL_') ? '待复核候选成员' : '候选簇成员' }

onMounted(doSearch)
</script>

<style scoped>
.page-header,.section-header,.filter-bar,.preview-meta{display:flex;align-items:center;justify-content:space-between;gap:12px}.page-header{margin-bottom:16px}.page-header h2,.section-header h3{margin:0}.page-header p,.section-header p{margin:5px 0 0;color:var(--text-secondary);font-size:13px}.preview-meta{align-items:flex-end;flex-direction:column;color:var(--text-secondary);font-size:12px}.analysis-error,.analysis-card{margin-bottom:16px}.overview-grid{display:grid;grid-template-columns:repeat(4,minmax(180px,1fr));gap:12px;margin-bottom:16px}.overview-card{min-height:148px;padding:16px;border:1px solid var(--border-color);border-radius:10px;background:var(--bg-card);color:inherit;text-align:left;cursor:pointer}.overview-card:hover,.overview-card.active{border-color:var(--color-primary);box-shadow:0 0 0 1px var(--color-primary)}.overview-card.active{background:var(--color-primary-light-9)}.overview-card span,.overview-card small{display:block;color:var(--text-secondary)}.overview-card b{display:block;margin:8px 0 2px;font-size:28px}.overview-card p{margin:10px 0 0;font-size:12px;line-height:1.5;color:var(--text-secondary)}.section-header>span{color:var(--text-secondary);font-size:13px}.filter-bar{justify-content:flex-start;flex-wrap:wrap;margin-bottom:16px}.problem-link{display:flex;flex-direction:column;gap:5px;border:0;padding:4px 0;background:none;text-align:left;cursor:pointer}.problem-link strong{color:var(--color-primary);font-size:15px;line-height:1.45}.problem-link span{color:var(--text-secondary);font-size:12px}.channel-chip{display:inline-flex;align-items:center;gap:5px;margin:3px 10px 3px 0;padding:3px 7px;border-radius:12px;background:var(--bg-body);font-size:12px}.cluster-pagination{display:flex;justify-content:flex-end;margin-top:16px}.classification-grid{display:grid;grid-template-columns:repeat(2,minmax(280px,1fr));gap:12px;margin-bottom:16px}.classification-card{position:relative;padding:16px;border:1px solid var(--border-color);border-radius:8px}.classification-card>div{display:flex;justify-content:space-between;gap:12px}.classification-card b{font-size:20px}.classification-card p{margin:8px 0;color:var(--text-secondary);font-size:13px;line-height:1.5}.classification-card small{color:var(--text-secondary)}.detail-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-bottom:16px}.detail-summary>div{display:flex;flex-direction:column;gap:6px;padding:12px;background:var(--bg-body);border-radius:8px}.detail-summary span{color:var(--text-secondary);font-size:12px}@media(max-width:1100px){.overview-grid{grid-template-columns:repeat(2,1fr)}.classification-grid{grid-template-columns:1fr}}
</style>
