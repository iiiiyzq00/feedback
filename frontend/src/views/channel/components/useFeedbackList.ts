import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { GovernedDiagnosticsDialogExpose } from '@/components/evidence/GovernedDiagnosticsDialog.types';
import {
  exportFeedback,
  getFeedbackExternalLink,
  getFeedbackList,
  toFeedbackChannelCsv,
  type FeedbackQuery,
  type FeedbackDiagnosticItem,
  type FeedbackListRow,
  type FeedbackListStats,
  type PhysicalChannelCode,
} from '@/api/feedback';
import { toLocalDateTimeString } from '@/utils/datetime';
import { openAuthorizedExternalWindow } from '@/utils/externalWindow';
import { useAuthStore } from '@/stores/auth';
import { hasFeedbackWorkspaceAccess } from '@/utils/feedbackWorkspaceAccess';
import { feedbackColumnsFor } from './channelColumns';

interface FeedbackFilters {
  dateRange: [Date, Date] | null;
  keyword: string;
}

interface SortChange {
  prop: string | null;
  order?: 'ascending' | 'descending' | null;
}

export interface FeedbackListProps {
  channel?: FeedbackListRow['sourceChannel'];
  view?: string;
  queryChannels?: PhysicalChannelCode[];
  extraFilters?: unknown[];
  defaultExtras?: Record<string, string>;
}

const sortableFields = [
  'reportedAt',
  'sourceUpdatedAt',
  'platformUpdatedAt',
  'severity',
  'status',
  'title',
] as const;

/**
 * 反馈反馈列表唯一拥有 URL 快照、分页、排序和导出查询构造。
 * 页面纠正仍复用同一查询入口；导出复用当前参数，防止列表与导出筛选语义漂移。
 */
export function useFeedbackList(
  props: Readonly<FeedbackListProps>,
) {
  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();
  const feedbackWorkspaceAccess = computed(() =>
    hasFeedbackWorkspaceAccess(auth.user, auth.hasPermission),
  );
  const loading = ref(false);
  const loadError = ref('');
  const list = ref<FeedbackListRow[]>([]);
  const newRecordsIgnored = ref(false);
  const diagnosticsDialog = ref<GovernedDiagnosticsDialogExpose>();
  const stats = reactive<FeedbackListStats>({
    total: 0,
    channelCount: 0,
    unassignedCount: 0,
    activeCount: 0,
    resolvedCount: 0,
    withEvidenceCount: 0,
    positiveCount: 0,
    negativeCount: 0,
    newRecords5m: 0,
  });
  const pagination = reactive({ page: 1, size: 20, total: 0 });
  const sorting = reactive<{
    sortBy: NonNullable<FeedbackQuery['sortBy']>;
    sortDirection: NonNullable<FeedbackQuery['sortDirection']>;
  }>({ sortBy: 'reportedAt', sortDirection: 'desc' });
  const filters = reactive<FeedbackFilters>({ dateRange: null, keyword: '' });
  const columns = computed(() =>
    feedbackColumnsFor(props.view || props.channel),
  );
  let searchTimer: number | undefined;

  function openDetail(row: FeedbackListRow) {
    void router.push({
      path: `/channel/${row.id}`,
      query: { from: route.fullPath },
    });
  }

  function openDiagnostics(
    row: FeedbackListRow,
    log?: FeedbackDiagnosticItem,
  ) {
    if (log?.attachmentId != null) {
      void diagnosticsDialog.value?.openLogDirect(Number(row.id), log);
      return;
    }
    void diagnosticsDialog.value?.open(Number(row.id));
  }

  function defaults() {
    filters.dateRange = null;
  }

  function hydrate() {
    defaults();
    const query = route.query;
    pagination.page = Number(query.page) || 1;
    pagination.size = Number(query.size) || 20;
    const requestedSort = String(query.sortBy || '');
    sorting.sortBy = sortableFields.includes(
      requestedSort as (typeof sortableFields)[number],
    )
      ? (requestedSort as NonNullable<FeedbackQuery['sortBy']>)
      : ['jira', 'station_feedback'].includes(props.channel || '')
        ? 'sourceUpdatedAt'
        : 'reportedAt';
    sorting.sortDirection =
      String(query.sortDirection).toLowerCase() === 'asc' ? 'asc' : 'desc';
    filters.keyword = String(query.keyword || '');
    if (query.date) {
      const date = new Date(`${String(query.date)}T00:00:00`);
      if (!Number.isNaN(date.getTime())) {
        filters.dateRange = [
          date,
          new Date(
            date.getFullYear(),
            date.getMonth(),
            date.getDate(),
            23,
            59,
            59,
            999,
          ),
        ];
      }
    } else if (query.startTime && query.endTime) {
      const start = new Date(String(query.startTime));
      const end = new Date(String(query.endTime));
      if (!Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime()))
        filters.dateRange = [start, end];
    } else if (query.days) {
      const days = Number(query.days);
      if (Number.isFinite(days) && days > 0) {
        const end = new Date();
        filters.dateRange = [new Date(end.getTime() - days * 86400000), end];
      }
    }
  }

  function params(): FeedbackQuery {
    const query: FeedbackQuery = {
      channel: props.channel,
      page: pagination.page,
      size: pagination.size,
      sortBy: sorting.sortBy,
      sortDirection: sorting.sortDirection,
    };
    if (props.queryChannels?.length)
      query.channels = toFeedbackChannelCsv(props.queryChannels);
    if (filters.keyword) query.keyword = filters.keyword;
    if (filters.dateRange) {
      query.startTime = toLocalDateTimeString(filters.dateRange[0]);
      query.endTime = toLocalDateTimeString(filters.dateRange[1]);
    }
    return query;
  }

  async function loadData() {
    loading.value = true;
    loadError.value = '';
    try {
      const query = params();
      await router.replace({
        query: Object.fromEntries(
          Object.entries(query)
            .filter(([key]) => key !== 'channel')
            .map(([key, value]) => [key, String(value)]),
        ),
      });
      const result = await getFeedbackList(query);
      list.value = result.records;
      pagination.total = result.total;
      Object.assign(stats, result.stats);
      if (!list.value.length && pagination.total > 0 && pagination.page > 1) {
        pagination.page = 1;
        await loadData();
      }
    } catch {
      loadError.value = '暂时无法获取反馈数据，请检查网络后重试';
    } finally {
      loading.value = false;
    }
  }

  function doSearch() {
    pagination.page = 1;
    void loadData();
  }
  function debounceSearch() {
    clearTimeout(searchTimer);
    searchTimer = window.setTimeout(() => {
      if (!filters.keyword || filters.keyword.length >= 2) doSearch();
    }, 300);
  }
  function loadNewest() {
    pagination.page = 1;
    newRecordsIgnored.value = false;
    void loadData();
  }
  function resetFilters() {
    filters.keyword = '';
    defaults();
    doSearch();
  }
  function pageChanged(page: number) {
    pagination.page = page;
    void loadData();
  }
  function sizeChanged(size: number) {
    pagination.size = size;
    pagination.page = 1;
    void loadData();
  }
  function sortChanged(change: SortChange) {
    sorting.sortBy =
      change.prop &&
      sortableFields.includes(change.prop as (typeof sortableFields)[number])
        ? (change.prop as NonNullable<FeedbackQuery['sortBy']>)
        : 'reportedAt';
    sorting.sortDirection = change.order === 'ascending' ? 'asc' : 'desc';
    doSearch();
  }

  async function exportCurrent() {
    const blob = await exportFeedback(undefined, params());
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `feedback-${new Date().toISOString().slice(0, 10)}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  async function openSource(row: FeedbackListRow) {
    try {
      const opened = await openAuthorizedExternalWindow(async () => {
        const result = await getFeedbackExternalLink(Number(row.id));
        return result.url;
      });
      if (opened === 'BLOCKED')
        ElMessage.warning('浏览器阻止了新标签页，请允许本站打开弹窗后重试');
    } catch {
      ElMessage.warning('当前账号无权访问来源链接，或尚未配置 Jira 域名');
    }
  }

  watch(
    () => props.channel,
    () => {
      list.value = [];
      diagnosticsDialog.value?.clear();
      hydrate();
      void loadData();
    },
  );
  onMounted(() => {
    hydrate();
    void loadData();
  });
  onBeforeUnmount(() => {
    clearTimeout(searchTimer);
    list.value = [];
    diagnosticsDialog.value?.clear();
  });

  return {
    feedbackWorkspaceAccess,
    loading,
    loadError,
    list,
    newRecordsIgnored,
    diagnosticsDialog,
    stats,
    pagination,
    sorting,
    filters,
    columns,
    openDetail,
    openDiagnostics,
    loadData,
    debounceSearch,
    doSearch,
    loadNewest,
    resetFilters,
    pageChanged,
    sizeChanged,
    sortChanged,
    exportCurrent,
    openSource,
  };
}
