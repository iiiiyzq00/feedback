<template>
  <ClusterShadowPreviewPage v-if="useVerifiedShadowAnalysis" mode="primary" />
  <div v-else class="page-container">
    <div class="page-header">
      <h2>问题深度分析</h2>
      <span class="metric-meta"
        >数据更新于 {{ formatTime(resultMeta.generatedAt) }}</span
      >
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <span>时间：</span>
        <el-date-picker
          v-model="filters.dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :default-time="rangeDefaultTime"
        />
        <span class="filter-hint">不选择时分析全部历史数据</span>
        <el-button type="primary" :loading="loading" @click="analyze"
          >分析</el-button
        >
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </el-card>

    <el-alert
      v-if="loadError"
      class="analysis-error"
      type="error"
      :closable="false"
      show-icon
      title="问题分析数据加载失败"
    >
      <template #default>
        <span>已保留上一次成功结果，请稍后重试。</span>
        <el-button text type="primary" :loading="loading" @click="doSearch()"
          >重新加载</el-button
        >
      </template>
    </el-alert>

    <el-card
      v-for="section in sections"
      :key="section.key"
      shadow="never"
      class="cluster-card"
    >
      <template #header>
        <div class="section-header">
          <span class="section-title">{{ section.title }}</span>
          <span>{{ section.description }}</span>
        </div>
      </template>
      <el-table
        :data="section.page.items"
        v-loading="loading"
        :empty-text="section.emptyText"
      >
        <el-table-column label="问题簇现象" min-width="300">
          <template #default="{ row }">
            <router-link
              class="cluster-link"
              :to="{
                name: 'ClusterDetail',
                params: { id: row.clusterId },
                query: detailQuery,
              }"
              >{{ row.phenomenon || '问题现象待补充' }}</router-link
            >
          </template>
        </el-table-column>
        <el-table-column prop="recordCount" label="反馈记录数" width="105" />
        <el-table-column label="用户数量" width="95"
          ><template #default="{ row }">{{
            metric(row.userCount)
          }}</template></el-table-column
        >
        <el-table-column label="设备数量" width="95"
          ><template #default="{ row }">{{
            deviceMetric(row.deviceCount)
          }}</template></el-table-column
        >
        <el-table-column label="影响应用" min-width="180">
          <template #default="{ row }">
            <div v-if="row.affectedApplications?.length" class="application-dimensions">
              <span v-for="item in row.affectedApplications" :key="`${item.application}-${item.count}`">
                <span>{{ item.application || '应用信息待补充' }}</span>
                <el-tag size="small" effect="plain">{{ item.count }}</el-tag>
              </span>
            </div>
            <span v-else>应用信息待补充</span>
          </template>
        </el-table-column>
        <el-table-column label="首次发生时间" width="170"
          ><template #default="{ row }">{{
            formatTime(row.firstOccurredAt)
          }}</template></el-table-column
        >
        <el-table-column label="最近发生时间" width="170"
          ><template #default="{ row }">{{
            formatTime(row.lastOccurredAt)
          }}</template></el-table-column
        >
        <el-table-column label="典型证据" min-width="130"
          ><template #default="{ row }">{{
            evidenceText(row)
          }}</template></el-table-column
        >
        <el-table-column label="涉及区域" min-width="160"
          ><template #default="{ row }">{{
            dimensionText(row.regions, 'region')
          }}</template></el-table-column
        >
        <el-table-column label="操作" width="72" fixed="right">
          <template #default="{ row }"
            ><router-link
              class="cluster-link"
              :to="{
                name: 'ClusterDetail',
                params: { id: row.clusterId },
                query: detailQuery,
              }"
              >详情</router-link
            ></template
          >
        </el-table-column>
      </el-table>
      <el-pagination
        class="cluster-pagination"
        layout="total, prev, pager, next"
        :total="section.page.total"
        :page-size="section.page.size"
        :current-page="section.page.page"
        @current-change="changePage(section.key, $event)"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import ClusterShadowPreviewPage from '@/views/admin/ClusterShadowPreviewPage.vue';
import { getProblemAnalysis } from '@/api/dashboard';
import { useAuthStore } from '@/stores/auth';
import type {
  ProblemClusterDimension,
  ProblemClusterItem,
  ProblemClusterPage,
} from '@/api/dashboard';
import { toLocalDateTimeString } from '@/utils/datetime';
import { regionName } from '@/utils/region';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
// 灰度只对管理员开放；生产构建默认启用，显式设为 false 可回到既有 ACTIVE 投影读路径。
const useVerifiedShadowAnalysis = computed(
  () => auth.isAdmin && import.meta.env.VITE_VERIFIED_SHADOW_ANALYSIS_ENABLED !== 'false',
);
const loading = ref(false);
const loadError = ref(false);
let searchSequence = 0;
const clusterSize = 20;
const emptyPage = (): ProblemClusterPage => ({
  items: [],
  total: 0,
  page: 1,
  size: clusterSize,
});
const allClusters = reactive<ProblemClusterPage>(emptyPage());
const crossChannelClusters = reactive<ProblemClusterPage>(emptyPage());
const resultMeta = reactive<{
  metricVersion: string;
  generatedAt: string | null;
}>({ metricVersion: '-', generatedAt: null });
// 详情页必须继承最近一次成功响应确认的范围，不能使用尚未点击“分析”的筛选草稿。
const resultRange = reactive<{
  startTime: string | null;
  endTime: string | null;
}>({ startTime: null, endTime: null });
const detailQuery = computed(() =>
  resultRange.startTime && resultRange.endTime
    ? { startTime: resultRange.startTime, endTime: resultRange.endTime }
    : {},
);
const filters = reactive<{ dateRange: [Date, Date] | null }>({
  dateRange: null,
});
const rangeDefaultTime = [
  new Date(2000, 0, 1, 0, 0, 0, 0),
  new Date(2000, 0, 1, 23, 59, 59, 999),
];
const sections = computed(() => [
  {
    key: 'all' as const,
    title: '全部去重问题簇明细',
    description: '所选时间范围内的全部问题簇',
    emptyText: '当前时间范围无去重问题簇',
    page: allClusters,
  },
  {
    key: 'crossChannel' as const,
    title: '跨渠道问题簇明细',
    description: '同一问题簇至少命中两个反馈渠道',
    emptyText: '当前时间范围无跨渠道问题簇',
    page: crossChannelClusters,
  },
]);

function normalizedRange(): [Date, Date] | null {
  if (!filters.dateRange) return null;
  const [rawStart, rawEnd] = filters.dateRange;
  const start = new Date(rawStart);
  const end = new Date(rawEnd);
  start.setHours(0, 0, 0, 0);
  end.setHours(23, 59, 59, 999);
  return [start, end];
}
function restoreRange() {
  if (
    typeof route.query.startTime === 'string' &&
    typeof route.query.endTime === 'string'
  ) {
    const start = new Date(route.query.startTime);
    const end = new Date(route.query.endTime);
    if (!Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime()))
      filters.dateRange = [start, end];
  }
  allClusters.page = validPage(route.query.allPage);
  crossChannelClusters.page = validPage(route.query.crossChannelPage);
}
function validPage(value: unknown) {
  const page = Number(value);
  return Number.isSafeInteger(page) && page > 0 ? page : 1;
}
async function doSearch(syncUrl = true) {
  // 分页和重新分析共用同一序列；较早响应不得覆盖较新的两个分页区或结果范围。
  const sequence = ++searchSequence;
  loading.value = true;
  loadError.value = false;
  try {
    const range = normalizedRange();
    const response = await getProblemAnalysis({
      ...(range
        ? {
            startTime: toLocalDateTimeString(range[0]),
            endTime: toLocalDateTimeString(range[1]),
          }
        : {}),
      allPage: allClusters.page,
      allSize: clusterSize,
      crossChannelPage: crossChannelClusters.page,
      crossChannelSize: clusterSize,
    });
    if (sequence !== searchSequence) return;
    Object.assign(allClusters, response.allClusters);
    Object.assign(crossChannelClusters, response.crossChannelClusters);
    Object.assign(resultMeta, {
      metricVersion: response.metricVersion,
      generatedAt: response.generatedAt,
    });
    Object.assign(resultRange, {
      startTime: response.startTime,
      endTime: response.endTime,
    });
    if (syncUrl)
      await router.replace({
        path: route.path,
        query: {
          ...(range
            ? {
                startTime: range[0].toISOString(),
                endTime: range[1].toISOString(),
              }
            : {}),
          ...(allClusters.page > 1
            ? { allPage: String(allClusters.page) }
            : {}),
          ...(crossChannelClusters.page > 1
            ? { crossChannelPage: String(crossChannelClusters.page) }
            : {}),
        },
      });
  } catch {
    // 加载失败保留最近一次成功结果，只显示可恢复错误状态。
    if (sequence === searchSequence) {
      loadError.value = true;
      ElMessage.error('深度分析加载失败');
    }
  } finally {
    if (sequence === searchSequence) loading.value = false;
  }
}
function resetFilters() {
  filters.dateRange = null;
  allClusters.page = 1;
  crossChannelClusters.page = 1;
  doSearch();
}
function analyze() {
  allClusters.page = 1;
  crossChannelClusters.page = 1;
  doSearch();
}
function changePage(section: 'all' | 'crossChannel', page: number) {
  if (section === 'all') allClusters.page = page;
  else crossChannelClusters.page = page;
  doSearch();
}
function metric(value: unknown) {
  return value == null ? '未知' : String(value);
}
function deviceMetric(value: unknown) {
  return value == null ? '0' : String(value);
}
function dimensionText(
  items: ProblemClusterDimension[] | undefined,
  key: 'application' | 'region',
) {
  if (!items?.length) return key === 'region' ? '暂无区域数据' : '-';
  return items
    .map(
      (item) =>
        `${key === 'region' ? regionName(item.region, '未知') : (item.application ?? '未知')}（${item.count}）`,
    )
    .join('、');
}
function evidenceText(row: ProblemClusterItem) {
  const parts = [
    ['截图', row.screenshotCount],
    ['日志', row.logCount],
    ['附件', row.attachmentCount],
  ]
    .filter(([, count]) => Number(count) > 0)
    .map(([name, count]) => `${name} ${count}`);
  return parts.length ? parts.join('、') : '无可用证据';
}
function formatTime(value: unknown) {
  if (!value) return '-';
  const date = new Date(String(value));
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN');
}
onMounted(() => {
  if (useVerifiedShadowAnalysis.value) return;
  restoreRange();
  doSearch(false);
});
</script>

<style scoped>
.page-header,
.section-header,
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.metric-meta,
.section-header span:last-child,
.filter-hint {
  font-size: 12px;
  color: var(--text-secondary);
}
.filter-card,
.cluster-card,
.analysis-error {
  margin-bottom: 16px;
}
.application-dimensions,.application-dimensions>span{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.application-dimensions{gap:8px 12px}
.filter-bar {
  justify-content: flex-start;
  flex-wrap: wrap;
}
.section-title {
  font-weight: 600;
}
.cluster-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.cluster-link {
  color: var(--el-color-primary);
  text-decoration: none;
}
.analysis-error :deep(.el-alert__content) {
  width: 100%;
}
.analysis-error :deep(.el-alert__description) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
}
</style>
