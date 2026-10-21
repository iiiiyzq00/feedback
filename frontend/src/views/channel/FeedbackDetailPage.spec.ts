import { describe, expect, it } from 'vitest';
import detailComponentSource from './FeedbackDetailPage.vue?raw';
import presentationSource from './feedbackDetailPresentation.ts?raw';
import readModelSource from './useFeedbackDetailReadModel.ts?raw';
import relatedSource from './useRelatedFeedbackPage.ts?raw';
import compatibilitySource from './useFeedbackDetailCompatibility.ts?raw';
import evidenceSource from '@/components/evidence/GovernedEvidenceViewer.vue?raw';
import diagnosticsSource from '@/components/evidence/GovernedDiagnosticsDialog.vue?raw';

const compact = (source: string) => source.replace(/\s+/g, '');
const detailSource = [
  detailComponentSource,
  presentationSource,
  readModelSource,
  relatedSource,
  compatibilitySource,
].join('\n');

describe('FeedbackDetailPage user-facing detail', () => {
  it('consolidates source facts without exposing platform ownership', () => {
    expect(detailSource).toContain(
      "detail.sourceChannel === 'jira' ? 'Jira经办人' : '来源经办人'",
    );
    expect(detailSource).toContain(
      "detail.sourceChannel === 'jira' ? 'Jira解决结果' : '来源解决结果'",
    );
    expect(detailSource).toContain('class="source-overview-grid section-body"');
    expect(detailSource).toContain('反馈来源与记录信息');
    expect(detailSource).not.toContain('<span>平台负责人</span>');
    expect(detailSource).not.toContain('openAssignment(');
    expect(detailSource).not.toContain('remindFeedback(');
    expect(detailSource).not.toContain('添加处置记录');
    expect(detailSource).not.toContain('v-model="handoffVisible"');
    expect(detailSource).not.toContain('v-model="statusVisible"');
  });

  it('summarizes SDK evidence needed for analysis', () => {
    expect(detailSource).toContain('GovernedEvidenceViewer');
    expect(detailSource).toContain(':log-attachments="logDiagnostics"');
    expect(evidenceSource).toContain(
      'streamAuthorizedFeedbackAttachment(grant.downloadUrl',
    );
    expect(evidenceSource).toContain('governedMediaKind(attachment)');
  });

  it('lists email as governed key information for Jira', () => {
    expect(compact(detailSource)).toContain(
      compact("jira: ['USER_ID', 'EMAIL', 'DEVICE_SN', 'APPLICATION_LOG']"),
    );
    expect(compact(detailSource)).toContain(compact("EMAIL: '邮箱'"));
  });

  it('loads a server-derived thumbnail while retaining click-only original viewing', () => {
    expect(evidenceSource).toContain('createObjectUrl(blob)');
    expect(evidenceSource).toContain('requestGovernedPreviewAuthorization');
    expect(evidenceSource).toContain('authorizeFeedbackAttachment(props.sourceId');
    expect(evidenceSource).not.toContain('createImageBitmap');
    expect(evidenceSource).not.toContain('canvas.toBlob');
  });

  it('keeps masking behavior without noisy masked suffixes in labels', () => {
    expect(detailSource).not.toContain('（已脱敏）');
    expect(detailSource).not.toContain('来源内容已脱敏');
  });

  it('shows diagnostic identities directly according to technical or product role', () => {
    expect(detailSource).toContain(
      'hasFeedbackWorkspaceAccess(auth.user, auth.hasPermission)',
    );
    expect(detailSource).toContain('GovernedDiagnosticsDialog');
    expect(detailSource).toContain("diagnosticsVisibility === 'RAW'");
    expect(detailSource).toContain('keyDiagnostics');
    expect(detailComponentSource).not.toContain('>直接查看</el-button');
    expect(readModelSource).toContain('viewFeedbackDiagnostics(sourceId)');
    expect(readModelSource).toContain('attachments.value = data?.attachments ?? []');
    expect(readModelSource).not.toContain('getFeedbackAttachments');
    expect(diagnosticsSource).toContain('@closed="clear"');
    expect(diagnosticsSource).toContain('diagnostics.value = []');
    expect(diagnosticsSource).toContain('visible.value = false');
    [
      'station_feedback:',
      "'AUTHENTICATED_USER_ID'",
      'quality:',
      'redblack:',
    ].forEach((token) => {
      expect(compact(detailSource)).toContain(compact(token));
    });
    expect(diagnosticsSource).toContain(
      "item.source === 'GOVERNED_ATTACHMENT'",
    );
    expect(diagnosticsSource).toContain('streamAuthorizedFeedbackAttachment');
    expect(diagnosticsSource).not.toContain('blob.text()');
  });

  it('downloads logs from their cached thumbnail preview and cancels work on navigation', () => {
    expect(diagnosticsSource).toContain("return '下载日志'");
    expect(diagnosticsSource).toContain('await downloadLogItem(item)');
    expect(diagnosticsSource).toContain('previewBytes: LOG_DETAIL_PREVIEW_BYTES');
    expect(evidenceSource).toContain('previewBytes: LOG_THUMBNAIL_BYTES');
    expect(diagnosticsSource).not.toContain("return '预览日志'");
    expect(compact(detailSource)).toContain(compact('disposed = true'));
    expect(compact(detailSource)).toContain(
      compact('detailLoadGeneration += 1'),
    );
    expect(compact(detailSource)).toContain(compact('related.dispose()'));
    expect(compact(detailSource)).toContain(compact('clearControlledState()'));
    expect(compact(detailSource)).toContain(compact('attachments.value = []'));
    expect(detailSource).toContain('delete detail.diagnosticAvailability');
    expect(diagnosticsSource).toContain('页面无需填写理由');
    expect(diagnosticsSource).not.toContain("'反馈日志页面内查看'");
    expect(diagnosticsSource).not.toContain('download=');
  });

  it('keeps legacy RCA data outside feedback accounts without loading disposition data', () => {
    expect(compact(detailSource)).toContain(
      compact('isFeedbackWorkspaceOnlyUser(auth.user, auth.hasPermission)'),
    );
    expect(compact(detailSource)).toContain(
      compact(
        'feedbackOnly.value ? Promise.resolve(emptyRcaKnowledge()) : getFeedbackRcaKnowledge',
      ),
    );
    expect(detailSource).not.toContain('getDispositionHistory');
  });

  it('uses governed named state instead of unconstrained any', () => {
    expect(detailSource).toContain('reactive<FeedbackDetailViewState>');
    expect(detailSource).toContain('ref<FeedbackTimelineEvent[]>');
    expect(detailSource).toContain('ref<FeedbackFieldHistory[]>');
    expect(detailSource).toContain('reactive<FeedbackRcaKnowledgeResponse>');
    expect(detailSource).not.toMatch(/\b(?:ref|reactive)<any\b/);
    expect(detailSource).not.toMatch(/\([^)]*:\s*any\b/);
    expect(detailSource).not.toMatch(/\bas\s+any\b/);
  });

  it('uses the popup-safe helper for Jira external links', () => {
    expect(compact(detailSource)).toContain(
      compact('openAuthorizedExternalWindow(async () =>'),
    );
    expect(detailSource).not.toContain('window.open(result.url');
  });

  it('returns to the originating channel list or cluster detail instead of relying on browser history', () => {
    expect(detailSource).toContain('@click="returnToList"');
    expect(detailSource).not.toContain('router.back()');
    expect(detailSource).toContain('route.query.from');
    expect(detailSource).toContain('clusterReturnPattern.test(from)');
    expect(detailSource).toContain('@click="returnToList">返回</el-button>');
    expect(detailSource).not.toContain('返回问题簇');
    expect(detailSource).not.toContain('返回列表');
    expect(detailSource).toContain(
      '/^\\/report\\/cluster\\/[1-9]\\d*(?:\\?|$)/',
    );
    expect(detailSource).toContain(
      "channelListPaths[detail.sourceChannel || ''] || '/channel/all'",
    );
    expect(detailSource).toContain(
      '/^\\/channel\\/(all|sdk|station-feedback|jira|quality|redblack)',
    );
  });

  it('uses aligned full-width sections without decorative expansion controls', () => {
    expect(detailSource).not.toContain('<div class="detail-column');
    expect(detailSource).toContain('horizontal-content--overview');
    expect(detailSource).toContain('horizontal-content--ai');
    expect(detailSource).toContain('horizontal-content--governance');
    expect(detailSource).not.toContain('展开全文');
    expect(detailSource).not.toContain('descriptionExpanded');
    expect(detailSource).not.toContain('aiCopyExpanded');
    expect(detailSource).toContain(
      '<style scoped src="./FeedbackDetailPage.css"></style>',
    );
  });

  it('shows concrete related feedback records with server-side pagination', () => {
    expect(detailSource).toContain(
      '每行对应一条具体反馈，不再用规范簇名代替反馈内容',
    );
    expect(detailSource).toContain(
      "row.feedbackContent || '该反馈暂无问题内容'",
    );
    expect(detailSource).toContain('channelLabel(row.sourceChannel)');
    expect(compact(detailSource)).toContain(compact('平台 #{{ row.id }}'));
    expect(compact(detailSource)).toContain(
      compact('getRelatedFeedback(sourceId, 1, related.relatedPageSize)'),
    );
    expect(detailSource).toContain('@current-change="changeRelatedPage"');
    expect(detailSource).toContain(':total="relatedTotal"');
    expect(detailSource).not.toContain('prop="title" label="标题"');
  });
});
