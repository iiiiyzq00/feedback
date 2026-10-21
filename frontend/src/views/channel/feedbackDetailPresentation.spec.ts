import { describe, expect, it } from 'vitest';
import {
  aiSources,
  channelLabel,
  diagnosticStates,
  diagnosticSummary,
  displayMetaValue,
  evidenceCounts,
  evidenceSummary,
  faultTimeEvidence,
  metaEntries,
  primaryTime,
  type FeedbackDetailView,
} from './feedbackDetailPresentation';
import type { FeedbackAttachmentSummary } from '@/api/feedback';

describe('feedback detail presentation', () => {
  it('presents channel metadata without leaking internal ingestion fields', () => {
    const detail: FeedbackDetailView = {
      sourceChannel: 'jira' as const,
      channelMeta: { jira_key: 'FB-1', ingest_status: 'DONE' },
    };
    expect(metaEntries(detail)).toEqual([
      { key: 'jira_key', label: 'Jira 编号', value: 'FB-1' },
    ]);
    expect(displayMetaValue('{"ok":true}')).toBe('{\n  "ok": true\n}');
  });

  it('keeps source-semantic time and diagnostic wording', () => {
    expect(primaryTime({ sourceChannel: 'quality' })).toEqual({
      label: '商返申请日期',
      evidenceLabel: '日期依据',
      value: '商返申请日期未提供',
    });
    expect(
      faultTimeEvidence({
        sourceChannel: 'redblack',
        faultTimeResolution: 'NOT_APPLICABLE',
      }),
    ).toBe('该渠道不产生故障时间');
    const states = diagnosticStates({
      sourceChannel: 'jira',
      diagnosticAvailability: { types: ['EMAIL'] },
    });
    expect(states.find((item) => item.type === 'EMAIL')).toMatchObject({
      label: '邮箱',
      available: true,
    });
    expect(diagnosticSummary(states)).toContain('邮箱可直接查看');
  });

  it('counts governed log availability without adding it to ordinary attachments', () => {
    const detail: FeedbackDetailView = {
      sourceChannel: 'sdk',
      diagnosticAvailability: { types: ['APPLICATION_LOG'] },
    };
    const attachments: FeedbackAttachmentSummary[] = [
      { id: 1, attachmentType: 'SCREENSHOT' },
    ];
    const counts = evidenceCounts(detail, attachments);
    expect(counts).toEqual({ image: 1, log: 1, video: 0, other: 0 });
    expect(evidenceSummary(counts)).toBe('截图/图片 1 · 日志 1');
    expect(channelLabel('station_feedback')).toContain('原反馈平台');
    expect(aiSources('["标题","日志"]')).toBe('标题、日志');
  });
});
