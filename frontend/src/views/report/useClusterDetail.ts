import { computed, onMounted, reactive, ref } from 'vue';
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router';
import type { GovernedDiagnosticsDialogExpose } from '@/components/evidence/GovernedDiagnosticsDialog.types';
import type { FeedbackDiagnosticItem } from '@/api/feedback';
import {
  getClusterDetail,
  getClusterFeedback,
  type ClusterDetailResponse,
  type ClusterFeedbackItem,
} from '@/api/report';
import {
  clusterStatusLabel as describeClusterStatus,
  formatDateTime,
} from './clusterPresentation';

const emptyCluster = (): ClusterDetailResponse => ({
  id: 0,
  name: '',
  canonicalSymptom: '',
  functionModule: null,
  issueCategory: null,
  totalFeedback: 0,
  channelCount: 0,
  latestTime: null,
  activeDays: 0,
  status: '',
  lifecycleVersion: 0,
  mergedIntoClusterId: null,
  splitFromClusterId: null,
  statusReason: null,
  uniqueUsers: null,
  uniqueDevices: 0,
  stationCount: 0,
  jiraCount: 0,
  qualityCount: 0,
  redblackCount: 0,
  firstTime: null,
});

function validPage(value: unknown) {
  const page = Number(value);
  return Number.isSafeInteger(page) && page > 0 ? page : 1;
}

/**
 * 问题簇详情统一冻结问题分析页传入的成对时间范围，并管理簇内反馈分页代次。
 * 缺少任一时间端点时按全部历史读取；并发翻页只允许最新请求提交状态。
 */
export function useClusterDetail(
  route: RouteLocationNormalizedLoaded,
  router: Router,
) {
  const pageLoading = ref(false);
  const pageLoaded = ref(false);
  const pageLoadError = ref(false);
  const cluster = reactive<ClusterDetailResponse>(emptyCluster());
  const diagnosticsDialog = ref<GovernedDiagnosticsDialogExpose>();
  const feedbackList = ref<ClusterFeedbackItem[]>([]);
  const feedbackPage = ref(validPage(route.query.feedbackPage));
  const feedbackPageSize = 20;
  const feedbackTotal = ref(0);
  const feedbackLoading = ref(false);
  const feedbackError = ref(false);
  let feedbackRequestSequence = 0;

  function analysisRange() {
    const startTime =
      typeof route.query.startTime === 'string'
        ? route.query.startTime
        : undefined;
    const endTime =
      typeof route.query.endTime === 'string' ? route.query.endTime : undefined;
    return startTime && endTime ? { startTime, endTime } : {};
  }

  const clusterStatusLabel = computed(() =>
    describeClusterStatus(cluster.status),
  );
  const analysisRangeLabel = computed(() => {
    const { startTime, endTime } = analysisRange();
    if (!startTime || !endTime) return '该问题簇全部可见记录';
    return `${formatDateTime(startTime)} 至 ${formatDateTime(endTime)}`;
  });

  function returnToAnalysis() {
    const analysisQuery = { ...route.query };
    delete analysisQuery.feedbackPage;
    void router.push({ path: '/dashboard/analysis', query: analysisQuery });
  }

  function openDiagnostics(
    row: ClusterFeedbackItem,
    log?: FeedbackDiagnosticItem,
  ) {
    if (log?.attachmentId != null) {
      void diagnosticsDialog.value?.openLogDirect(Number(row.id), log);
      return;
    }
    void diagnosticsDialog.value?.open(Number(row.id));
  }

  async function loadFeedback() {
    const id = Number(route.params.id);
    if (!Number.isSafeInteger(id) || id <= 0) return;
    const sequence = ++feedbackRequestSequence;
    feedbackLoading.value = true;
    feedbackError.value = false;
    try {
      const result = await getClusterFeedback(id, {
        page: feedbackPage.value,
        size: feedbackPageSize,
        ...analysisRange(),
      });
      if (sequence !== feedbackRequestSequence) return;
      feedbackList.value = result.items;
      feedbackTotal.value = result.total;
      if (
        result.total > 0 &&
        result.items.length === 0 &&
        feedbackPage.value > 1
      ) {
        feedbackPage.value = Math.max(
          1,
          Math.ceil(result.total / feedbackPageSize),
        );
        await loadFeedback();
      }
    } catch {
      if (sequence === feedbackRequestSequence) feedbackError.value = true;
    } finally {
      if (sequence === feedbackRequestSequence) feedbackLoading.value = false;
    }
  }

  async function changeFeedbackPage(page: number) {
    feedbackPage.value = page;
    await router.replace({
      path: route.path,
      query: {
        ...route.query,
        ...(page > 1
          ? { feedbackPage: String(page) }
          : { feedbackPage: undefined }),
      },
    });
    await loadFeedback();
  }

  async function loadPage() {
    const id = Number(route.params.id);
    if (!Number.isSafeInteger(id) || id <= 0) {
      pageLoadError.value = true;
      return;
    }
    pageLoading.value = true;
    pageLoadError.value = false;
    try {
      const summary = await getClusterDetail(id, analysisRange());
      Object.assign(cluster, summary);
      pageLoaded.value = true;
      await loadFeedback();
    } catch {
      pageLoadError.value = true;
    } finally {
      pageLoading.value = false;
    }
  }

  onMounted(() => {
    void loadPage();
  });

  return {
    pageLoading,
    pageLoaded,
    pageLoadError,
    cluster,
    clusterStatusLabel,
    analysisRangeLabel,
    diagnosticsDialog,
    feedbackList,
    feedbackPage,
    feedbackPageSize,
    feedbackTotal,
    feedbackLoading,
    feedbackError,
    returnToAnalysis,
    openDiagnostics,
    loadFeedback,
    changeFeedbackPage,
    loadPage,
  };
}
