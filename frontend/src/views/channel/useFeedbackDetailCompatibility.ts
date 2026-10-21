import type { Ref } from 'vue';
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  getFeedbackExternalLink,
  type FeedbackSourceChannel,
} from '@/api/feedback';
import { openAuthorizedExternalWindow } from '@/utils/externalWindow';

const channelListPaths: Record<string, string> = {
  sdk: '/channel/station-feedback',
  station_feedback: '/channel/station-feedback',
  jira: '/channel/jira',
  quality: '/channel/quality',
  redblack: '/channel/redblack',
};
const clusterReturnPattern = /^\/report\/cluster\/[1-9]\d*(?:\?|$)/;
const channelReturnPattern =
  /^\/channel\/(all|sdk|station-feedback|jira|quality|redblack)(?:\?|$)/;

/** 保留详情页既有返回路径、相邻记录导航、复制和受控外链行为。 */
export function useFeedbackDetailCompatibility(
  route: RouteLocationNormalizedLoaded,
  router: Router,
  feedbackId: Ref<number>,
  detail: {
    id?: number;
    sourceRecordId?: string | null;
    sourceChannel?: FeedbackSourceChannel | '';
  },
) {
  function returnOrigin() {
    const from = Array.isArray(route.query.from)
      ? route.query.from[0]
      : route.query.from;
    return typeof from === 'string' ? from : '';
  }

  function listReturnPath() {
    const from = returnOrigin();
    if (clusterReturnPattern.test(from) || channelReturnPattern.test(from))
      return from;
    return channelListPaths[detail.sourceChannel || ''] || '/channel/all';
  }

  function returnToList() {
    void router.push(listReturnPath());
  }

  function go(target?: number | null) {
    if (!target) return;
    void router.push({
      path: `/channel/${target}`,
      query: route.query.from ? { from: route.query.from } : {},
    });
  }

  async function copyId() {
    await navigator.clipboard.writeText(
      String(detail.sourceRecordId || detail.id),
    );
    ElMessage.success('ID 已复制');
  }

  async function openExternal() {
    try {
      const opened = await openAuthorizedExternalWindow(async () => {
        const result = await getFeedbackExternalLink(feedbackId.value);
        return result.url;
      });
      if (opened === 'BLOCKED')
        ElMessage.warning('浏览器阻止了新标签页，请允许本站打开弹窗后重试');
    } catch {
      ElMessage.warning('当前账号无权访问来源链接，或尚未配置 Jira 域名');
    }
  }

  return { returnToList, go, copyId, openExternal };
}
