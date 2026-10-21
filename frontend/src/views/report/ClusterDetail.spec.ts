import { describe, expect, it } from 'vitest';
import pageComponentSource from './ClusterDetail.vue?raw';
import pageModelSource from './useClusterDetail.ts?raw';
import pagePresentationSource from './clusterPresentation.ts?raw';
import apiSource from '@/api/report.ts?raw';
import evidenceSource from '@/components/evidence/GovernedEvidenceViewer.vue?raw';
import diagnosticsSource from '@/components/evidence/GovernedDiagnosticsDialog.vue?raw';

const compact = (source: string) => source.replace(/\s+/g, '');
const pageSource = [
  pageComponentSource,
  pageModelSource,
  pagePresentationSource,
].join('\n');

describe('ClusterDetail analysis usability contract', () => {
  it('pages cluster feedback instead of loading an unbounded list', () => {
    expect(apiSource).toContain('interface ClusterFeedbackPage');
    expect(apiSource).toContain(
      'interface ClusterFeedbackRequest extends ClusterTimeRangeRequest',
    );
    expect(apiSource).toContain('params: ClusterFeedbackRequest');
    expect(pageSource).toContain(':total="feedbackTotal"');
    expect(pageSource).toContain('@current-change="changeFeedbackPage"');
    expect(compact(pageSource)).toContain(
      compact('feedbackPage: String(page)'),
    );
  });

  it('keeps clustering explanations visible on every feedback row', () => {
    ['归簇方式', '程序相似度', '模型置信度', '活动模型', '归簇依据'].forEach(
      (label) => expect(pageSource).toContain(label),
    );
  });

  it('shows complete safe feedback content without overflow truncation', () => {
    expect(compact(pageSource)).toContain(
      compact('prop="issueContent" label="问题内容"'),
    );
    expect(pageSource).toContain(
      '<style scoped src="./ClusterDetail.css"></style>',
    );
    expect(pageSource).not.toContain(
      'prop="title" label="标题" min-width="200" show-overflow-tooltip',
    );
  });

  it('discards stale feedback page responses', () => {
    expect(compact(pageSource)).toContain(
      compact('const sequence = ++feedbackRequestSequence'),
    );
    expect(compact(pageSource)).toContain(
      compact('if (sequence !== feedbackRequestSequence) return'),
    );
  });

  it('normalizes invalid page URLs and provides recoverable loading errors', () => {
    expect(compact(pageSource)).toContain(
      compact('Number.isSafeInteger(page) && page > 0'),
    );
    expect(pageSource).toContain('v-if="pageLoadError"');
    expect(pageSource).toContain('@click="loadPage"');
    expect(pageSource).toContain('v-if="feedbackError"');
    expect(pageSource).toContain('@click="loadFeedback"');
  });

  it('uses the selected analysis range for detail statistics and feedback pages', () => {
    expect(apiSource).toContain('interface ClusterTimeRangeRequest');
    expect(pageSource).toContain('function analysisRange()');
    expect(compact(pageSource)).toContain(
      compact('getClusterDetail(id, analysisRange())'),
    );
    expect(pageSource).toContain('...analysisRange()');
  });

  it('uses product-facing labels and shows SN-based device counts', () => {
    expect(pageSource).toContain('反馈记录数');
    expect(pageSource).toContain('用户数量');
    expect(pageSource).toContain('设备数量');
    expect(compact(pageSource)).toContain(
      compact('学习机 {{ cluster.stationCount }}'),
    );
    expect(compact(pageSource)).toContain(compact('uniqueDevices: 0'));
    expect(pageSource).not.toContain('SDK {{cluster.sdkCount}}');
    expect(pageSource).toContain('{{ clusterStatusLabel }}');
    expect(pageSource).toContain('channelLabel(row.sourceChannel)');
  });

  it('shows role-based information, direct evidence and source-semantic times without severity', () => {
    ['关键信息', '分析证据', '故障/发布时间', '故障时间', '发布时间'].forEach(
      (label) => expect(pageSource).toContain(label),
    );
    expect(pageSource).toContain('openDiagnostics(row)');
    expect(pageSource).toContain('GovernedEvidenceViewer');
    expect(pageSource).not.toContain('label="严重度"');
    expect(pageSource).not.toContain('最高反馈严重度');
    expect(pageSource).not.toContain('簇内反馈严重度分布');
  });

  it('uses the same direct role-based access APIs as channel pages', () => {
    [
      'viewFeedbackDiagnostics',
      'authorizeFeedbackAttachment',
      'streamAuthorizedFeedbackAttachment',
    ].forEach((name) => expect(diagnosticsSource).toContain(name));
    expect(evidenceSource).toContain('getFeedbackAttachments');
    expect(pageSource).not.toContain('用途长度必须为3～200字');
    expect(pageSource).not.toContain('理由长度必须为3～200字');
    expect(diagnosticsSource).toContain('当前日志中的敏感片段已隐藏');
  });

  it('downloads logs directly and preloads only the bounded thumbnail preview', () => {
    expect(diagnosticsSource).toContain("return '下载日志'");
    expect(diagnosticsSource).toContain('await downloadLogItem(item)');
    expect(diagnosticsSource).toContain('previewBytes: LOG_DETAIL_PREVIEW_BYTES');
    expect(evidenceSource).toContain('previewBytes: LOG_THUMBNAIL_BYTES');
    expect(diagnosticsSource).not.toContain("return '预览日志'");
  });

  it('clears sensitive values and object URLs when dialogs close or the page unmounts', () => {
    expect(diagnosticsSource).toContain('@closed="clear"');
    expect(diagnosticsSource).toContain('session.cancelAll()');
    expect(evidenceSource).toContain('@closed="closeDialog"');
    expect(evidenceSource).toContain('session.cancelAll()');
    expect(evidenceSource).toContain('session.revokeObjectUrl');
  });

  it('uses the standard detail action without truncated view copy', () => {
    expect(compact(pageSource)).toContain(compact('>详情</router-link>'));
    expect(compact(pageSource)).toContain(
      compact('query: { from: route.fullPath }'),
    );
    expect(pageSource).not.toContain('>查看</el-button>');
  });

  it('returns directly to problem analysis while preserving its filters', () => {
    expect(pageSource).toContain('@click="returnToAnalysis"');
    expect(compact(pageSource)).toContain(
      compact('@click="returnToAnalysis">返回</el-button>'),
    );
    expect(pageSource).not.toContain('返回问题深度分析');
    expect(compact(pageSource)).toContain(
      compact(
        "router.push({ path: '/dashboard/analysis', query: analysisQuery })",
      ),
    );
    expect(pageSource).toContain('delete analysisQuery.feedbackPage');
    expect(pageSource).not.toContain('$router.back()');
  });

  it('shows the active statistics range and excludes legacy operations from feedback workspace detail', () => {
    expect(pageSource).toContain('统计范围');
    expect(pageSource).toContain('{{ analysisRangeLabel }}');
    [
      '拆分簇',
      '合并其他簇',
      '发起新 RCA 分析',
      'RCA历史',
      'Incident记录',
      '知识沉淀',
      '建议复核严重度分类',
    ].forEach((label) => expect(pageSource).not.toContain(label));
    expect(pageSource).not.toContain("from '@/api/cluster'");
  });
});
