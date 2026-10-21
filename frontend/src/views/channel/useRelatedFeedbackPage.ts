import { ref, type Ref } from 'vue';
import { getRelatedFeedback, type RelatedFeedbackItem } from '@/api/feedback';

/**
 * 管理详情页“相关反馈”的独立分页代次。
 * 路由切换、整页重载和快速翻页都可能并发；调用方必须先失效旧代次，
 * 只有当前反馈 ID、页码和代次同时匹配的响应才允许提交。
 */
export function useRelatedFeedbackPage(feedbackId: Ref<number>) {
  const relatedRecords = ref<RelatedFeedbackItem[]>([]);
  const relatedTotal = ref(0);
  const relatedPage = ref(1);
  const relatedPageSize = 10;
  const relatedLoading = ref(false);
  let loadGeneration = 0;
  let disposed = false;

  function beginInitialLoad() {
    const generation = ++loadGeneration;
    relatedPage.value = 1;
    relatedLoading.value = true;
    return generation;
  }

  function commitInitialLoad(
    generation: number,
    result:
      | { records?: RelatedFeedbackItem[]; total?: number }
      | null
      | undefined,
  ) {
    if (disposed || generation !== loadGeneration) return false;
    relatedRecords.value = result?.records ?? [];
    relatedTotal.value = Number(result?.total ?? 0);
    return true;
  }

  function finishInitialLoad(generation: number) {
    if (generation === loadGeneration) relatedLoading.value = false;
  }

  async function loadRelated() {
    const generation = ++loadGeneration;
    const sourceId = feedbackId.value;
    const page = relatedPage.value;
    relatedLoading.value = true;
    try {
      const result = await getRelatedFeedback(sourceId, page, relatedPageSize);
      if (
        disposed ||
        generation !== loadGeneration ||
        sourceId !== feedbackId.value
      )
        return;
      relatedRecords.value = result?.records ?? [];
      relatedTotal.value = Number(result?.total ?? 0);
    } finally {
      if (generation === loadGeneration) relatedLoading.value = false;
    }
  }

  function changeRelatedPage(page: number) {
    relatedPage.value = page;
    void loadRelated();
  }

  function invalidate() {
    loadGeneration += 1;
  }

  function dispose() {
    disposed = true;
    invalidate();
  }

  return {
    relatedRecords,
    relatedTotal,
    relatedPage,
    relatedPageSize,
    relatedLoading,
    beginInitialLoad,
    commitInitialLoad,
    finishInitialLoad,
    loadRelated,
    changeRelatedPage,
    invalidate,
    dispose,
  };
}
