import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import type { RouteLocationNormalizedLoaded } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  getFeedbackDetail,
  getRelatedFeedback,
  getFeedbackTimeline,
  viewFeedbackDiagnostics,
  type FeedbackAttachmentSummary,
  type FeedbackContentVisibility,
  type FeedbackDetail,
  type FeedbackDiagnosticItem,
  type FeedbackFieldHistory,
  type FeedbackTimelineEvent,
  type RelatedFeedbackItem,
} from '@/api/feedback';
import type { GovernedDiagnosticsDialogExpose } from '@/components/evidence/GovernedDiagnosticsDialog.types';
import { unlinkCluster } from '@/api/cluster';
import {
  getFeedbackRcaKnowledge,
  type FeedbackRcaKnowledgeResponse,
} from '@/api/report';
import { useAuthStore } from '@/stores/auth';
import {
  hasFeedbackWorkspaceAccess,
  isFeedbackWorkspaceOnlyUser,
} from '@/utils/feedbackWorkspaceAccess';
import {
  diagnosticStates,
  diagnosticSummary,
  displayMetaValue,
  evidenceCounts as countEvidence,
  evidenceSummary as summarizeEvidence,
  faultTimeEvidence as describeFaultTime,
  metaEntries,
  metaValue,
  primaryTime,
  type FeedbackDetailView,
} from './feedbackDetailPresentation';
import { useRelatedFeedbackPage } from './useRelatedFeedbackPage';

type FeedbackDetailViewState = Omit<
  Partial<FeedbackDetail>,
  'sourceChannel' | 'channelMeta'
> &
  FeedbackDetailView;

const emptyRcaKnowledge = (): FeedbackRcaKnowledgeResponse => ({
  cluster: null,
  rca: [],
  knowledge: [],
});

/**
 * 详情页读取模型统一拥有路由快照、受控证据状态和请求代次。
 * 详情、时间轴、附件、相关反馈与 RCA 必须作为同一反馈 ID 的快照提交；
 * 路由变化和卸载会先失效在途请求，避免跨反馈拼接敏感数据。
 */
export function useFeedbackDetailReadModel(
  route: RouteLocationNormalizedLoaded,
) {
  const id = computed(() => Number(route.params.id));
  const auth = useAuthStore();
  const feedbackOnly = computed(() =>
    isFeedbackWorkspaceOnlyUser(auth.user, auth.hasPermission),
  );
  const feedbackWorkspaceAccess = computed(() =>
    hasFeedbackWorkspaceAccess(auth.user, auth.hasPermission),
  );
  const loading = ref(false);
  const showFieldHistory = ref(false);
  const timeline = ref<FeedbackTimelineEvent[]>([]);
  const fieldHistory = ref<FeedbackFieldHistory[]>([]);
  const attachments = ref<FeedbackAttachmentSummary[]>([]);
  const diagnostics = ref<FeedbackDiagnosticItem[]>([]);
  const diagnosticsVisibility = ref<FeedbackContentVisibility>('PARTIALLY_HIDDEN');
  const diagnosticsError = ref('');
  const diagnosticsDialog = ref<GovernedDiagnosticsDialogExpose>();
  const detail = reactive<FeedbackDetailViewState>({
    sourceChannel: '',
    navigation: {},
    clusterSummary: null,
  });
  const rcaKnowledge =
    reactive<FeedbackRcaKnowledgeResponse>(emptyRcaKnowledge());
  const related = useRelatedFeedbackPage(id);
  let detailLoadGeneration = 0;
  let disposed = false;

  const channelMetaEntries = computed(() => metaEntries(detail));
  const sourceAssignee = computed(() =>
    displayMetaValue(metaValue(detail, 'assignee') ?? '未提供'),
  );
  const sourceResolution = computed(() =>
    displayMetaValue(
      metaValue(detail, 'resolution') ??
        (metaValue(detail, 'resolved_at') ? '来源已解决' : '未解决/不适用'),
    ),
  );
  const primaryTimeState = computed(() => primaryTime(detail));
  const primaryTimeLabel = computed(() => primaryTimeState.value.label);
  const timeEvidenceLabel = computed(
    () => primaryTimeState.value.evidenceLabel,
  );
  const primaryTimeValue = computed(() => primaryTimeState.value.value);
  const faultTimeEvidence = computed(() => describeFaultTime(detail));
  const evidenceCounts = computed(() =>
    countEvidence(detail, attachments.value),
  );
  const evidenceSummary = computed(() =>
    summarizeEvidence(evidenceCounts.value),
  );
  const diagnosticFieldStates = computed(() => diagnosticStates(detail));
  const diagnosticAvailabilitySummary = computed(() =>
    diagnosticSummary(diagnosticFieldStates.value),
  );

  function channelMetaValue(key: string) {
    return metaValue(detail, key);
  }

  function clearControlledState() {
    diagnosticsDialog.value?.clear();
  }

  function openLog(item: FeedbackDiagnosticItem) {
    diagnosticsDialog.value?.openLog(id.value, item, {
      diagnostics: diagnostics.value,
      contentVisibility: diagnosticsVisibility.value,
      responseCacheable: false,
      sourceId: id.value,
      usageRestriction: 'PAGE_ONLY',
    });
  }

  async function loadDetail() {
    const generation = ++detailLoadGeneration;
    const relatedGeneration = related.beginInitialLoad();
    const sourceId = id.value;
    loading.value = true;
    try {
      const [data, events, relatedResult, diagnosticResult, knowledge] = await Promise.all(
        [
          getFeedbackDetail(sourceId),
          getFeedbackTimeline(sourceId),
          getRelatedFeedback(sourceId, 1, related.relatedPageSize),
          feedbackWorkspaceAccess.value
            ? viewFeedbackDiagnostics(sourceId).then(
                (value) => ({ value, error: '' }),
                () => ({ value: null, error: '关键信息暂时无法读取，请稍后重试' }),
              )
            : Promise.resolve({ value: null, error: '' }),
          feedbackOnly.value
            ? Promise.resolve(emptyRcaKnowledge())
            : getFeedbackRcaKnowledge(sourceId),
        ],
      );
      if (
        disposed ||
        generation !== detailLoadGeneration ||
        sourceId !== id.value
      )
        return;
      Object.keys(detail).forEach((key) => Reflect.deleteProperty(detail, key));
      Object.assign(detail, data);
      fieldHistory.value = data?.fieldHistory ?? [];
      timeline.value = events ?? [];
      attachments.value = data?.attachments ?? [];
      diagnostics.value = diagnosticResult.value?.diagnostics ?? [];
      diagnosticsVisibility.value =
        diagnosticResult.value?.contentVisibility ?? 'PARTIALLY_HIDDEN';
      diagnosticsError.value = diagnosticResult.error;
      related.commitInitialLoad(relatedGeneration, relatedResult);
      Object.assign(rcaKnowledge, knowledge);
    } finally {
      if (generation === detailLoadGeneration) loading.value = false;
      related.finishInitialLoad(relatedGeneration);
    }
  }

  async function unlinkRelated(record: RelatedFeedbackItem) {
    const reason = await ElMessageBox.prompt(
      '请输入解除关联理由（3～500字）',
      '解除主关联',
      {
        inputValidator: (value) =>
          (value.trim().length >= 3 && value.trim().length <= 500) ||
          '理由长度必须为3～500字',
      },
    ).then((result) => result.value.trim());
    await unlinkCluster(Number(record.id), {
      expectedVersion: Number(record.reviewVersion ?? 0),
      requestId: crypto.randomUUID(),
      reason,
    });
    ElMessage.success('关联已解除');
    await loadDetail();
  }

  onMounted(loadDetail);
  watch(
    () => route.params.id,
    () => {
      detailLoadGeneration += 1;
      related.invalidate();
      attachments.value = [];
      diagnostics.value = [];
      diagnosticsVisibility.value = 'PARTIALLY_HIDDEN';
      diagnosticsError.value = '';
      delete detail.diagnosticAvailability;
      clearControlledState();
      void loadDetail();
    },
  );
  onBeforeUnmount(() => {
    disposed = true;
    detailLoadGeneration += 1;
    related.dispose();
    clearControlledState();
  });

  return {
    id,
    feedbackOnly,
    loading,
    showFieldHistory,
    timeline,
    fieldHistory,
    attachments,
    diagnostics,
    diagnosticsVisibility,
    diagnosticsError,
    diagnosticsDialog,
    detail,
    rcaKnowledge,
    channelMetaEntries,
    channelMetaValue,
    sourceAssignee,
    sourceResolution,
    primaryTimeLabel,
    timeEvidenceLabel,
    primaryTimeValue,
    faultTimeEvidence,
    evidenceCounts,
    evidenceSummary,
    diagnosticFieldStates,
    diagnosticAvailabilitySummary,
    openLog,
    unlinkRelated,
    loadDetail,
    ...related,
  };
}
