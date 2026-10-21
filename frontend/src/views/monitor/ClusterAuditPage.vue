<template>
  <div class="page-container" data-testid="cluster-audit-page">
    <PageHeader title="同类问题审核" description="核对系统推荐的同类问题，确认后将用于趋势分析和问题跟踪。" />
    <el-alert title="处理优先级用于安排审核顺序，不等同于反馈严重度" :description="policyDescription" type="info" :closable="false" class="priority-guide" />
    <FilterPanel>
      <span class="filter-label">处理优先级</span>
      <el-radio-group v-model="priority" size="small">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="P1">紧急</el-radio-button>
        <el-radio-button value="P2">较高</el-radio-button>
        <el-radio-button value="P3">普通</el-radio-button>
      </el-radio-group>
    </FilterPanel>
    <ClusterReviewList
      :items="items"
      :loading="isFetching"
      :error="queryError"
      :pagination="pagination"
      @retry="refetch"
      @select="selected = $event"
      @page-change="pagination.page = $event"
      @size-change="changeSize"
    />
    <div v-if="selected" class="detail">
      <ClusterReviewDetail
        :item="selected"
        @merge="confirmMerge"
        @reopen="reopenResolved"
        @govern="govern"
        @create="createNew"
        @reject="rejectAll"
        @skip="skip"
        @unlink="unlink"
      />
    </div>
    <el-empty v-else description="选择上方记录查看候选问题簇对比" />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watchEffect } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import ClusterReviewList from './components/ClusterReviewList.vue'
import ClusterReviewDetail from './components/ClusterReviewDetail.vue'
import {
  confirmCluster,
  createCluster,
  getClusterReviews,
  getClusterReviewPolicy,
  rejectCluster,
  skipCluster,
  unlinkCluster,
  reopenCluster,
  type ClusterCandidate,
  type ClusterReviewItem,
  type ReviewCommand,
} from '@/api/cluster'
import { queryKeys } from '@/query/client'

const router = useRouter()
const client = useQueryClient()
const priority = ref('')
const selected = ref<ClusterReviewItem | null>(null)
const pagination = reactive({ page: 1, size: 20, total: 0 })
const policyQuery = useQuery({ queryKey: ['cluster-review-policy'], queryFn: getClusterReviewPolicy, staleTime: 300_000 })
const policyDescription = computed(() => {
  const policy = policyQuery.data.value
  if (!policy) return 'P1：S0/S1高风险；P2：S2、疑似复发或竞争热点；P3：其他边界项。'
  const timing = policy.slaEnabled
    ? `P1 SLA ${policy.sla.P1}；P2 SLA ${policy.sla.P2}；P3 SLA ${policy.sla.P3}`
    : '当前为开发阶段，审核任务保留优先级但暂不计算逾期'
  return `${policy.severityRelationship}。${timing}；竞争候选分差≤${policy.competitionMargin}。`
})
const query = useQuery({
  queryKey: computed(() => queryKeys.clusters.reviews(priority.value, pagination.page, pagination.size)),
  queryFn: () => getClusterReviews({ priority: priority.value, page: pagination.page, size: pagination.size }),
  placeholderData: (old) => old,
})
const items = computed(() => query.data.value?.records ?? [])
const isFetching = computed(() => query.isFetching.value)
const queryError = computed(() => query.error.value ? '暂时无法获取待审核任务，请稍后重试' : '')
watchEffect(() => { pagination.total = query.data.value?.total ?? 0 })
watchEffect(() => { priority.value; pagination.page = 1; selected.value = null })

function changeSize(value: number) { pagination.size = value; pagination.page = 1 }
function refetch() { void query.refetch() }
function govern(id: number) { void router.push(`/report/cluster/${id}`) }

async function command(action: string): Promise<ReviewCommand> {
  const active = selected.value
  if (!active) throw new Error('CLUSTER_REVIEW_SELECTION_REQUIRED')
  const reason = await ElMessageBox.prompt(`请输入${action}理由（3～500字）`, '审核依据', {
    inputValidator: (value) => value.trim().length >= 3 && value.trim().length <= 500 || '理由长度必须为3～500字',
    confirmButtonText: '确认',
    cancelButtonText: '取消',
  }).then((result) => result.value.trim())
  return { expectedVersion: active.reviewVersion, requestId: crypto.randomUUID(), reason }
}

async function run(action: () => Promise<unknown>, message: string) {
  try {
    await action()
    ElMessage.success(message)
    selected.value = null
    await client.invalidateQueries({ queryKey: queryKeys.clusters.all })
  } catch (error: unknown) {
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === 409) {
      ElMessage.warning('任务已被其他审核员处理，列表已刷新')
      selected.value = null
      await client.invalidateQueries({ queryKey: queryKeys.clusters.all })
    } else if (error !== 'cancel') throw error
  }
}

async function confirmMerge(candidate: ClusterCandidate) {
  const active = selected.value
  if (!active) return
  const cmd = await command('确认归入')
  await run(() => confirmCluster(active.id, candidate.clusterId, cmd), `已确认归入簇：${candidate.clusterName}`)
}
async function reopenResolved(candidate: ClusterCandidate) {
  const active = selected.value
  if (!active) return
  const cmd = await command('复发重开')
  await run(() => reopenCluster(candidate.clusterId, active.id, { ...cmd, expectedVersion: Number(candidate.lifecycleVersion) }), `已重开问题簇：${candidate.clusterName}`)
}
async function createNew(name: string) {
  const active = selected.value
  if (!active) return
  if (!name.trim()) return void ElMessage.warning('请输入新问题簇名称')
  const cmd = await command('新建簇')
  await run(() => createCluster(active.id, name.trim(), cmd), `已创建新簇：${name}`)
}
async function rejectAll() {
  const active = selected.value
  if (!active) return
  const cmd = await command('拒绝全部候选')
  await run(() => rejectCluster(active.id, cmd), '已拒绝全部候选')
}
async function skip() {
  const active = selected.value
  if (!active) return
  const cmd = await command('暂缓/待补充')
  await run(() => skipCluster(active.id, cmd), '已暂缓，待补充信息后重新评估')
}
async function unlink() {
  const active = selected.value
  if (!active) return
  const cmd = await command('解除关联')
  await run(() => unlinkCluster(active.id, cmd), '已解除关联')
}
</script>

<style scoped>
.priority-guide{margin-bottom:12px}.filter-label{font-size:13px;color:var(--text-secondary)}.detail{margin-top:16px;min-width:0}.detail :deep(.el-card__body){padding:18px}
</style>
