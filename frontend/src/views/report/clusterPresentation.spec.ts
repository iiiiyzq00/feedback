import { describe, expect, it } from 'vitest';
import {
  channelLabel,
  clusterStatusLabel,
  confidenceText,
  controlledInfoLabel,
  controlledInfoTypes,
  decisionLabel,
  feedbackTimeLabel,
  metric,
} from './clusterPresentation';
import type { ClusterFeedbackItem } from '@/api/report';

const clusterRow = (
  overrides: Partial<ClusterFeedbackItem>,
): ClusterFeedbackItem => ({
  id: 1,
  sourceRecordId: null,
  issueContent: '无法登录',
  sourceChannel: 'jira',
  reportedAt: null,
  controlledInfoTypes: null,
  attachmentCount: 0,
  screenshotCount: 0,
  logCount: 0,
  videoCount: 0,
  decisionType: null,
  similarity: null,
  decisionReason: null,
  aiModel: null,
  aiConfidence: null,
  aiAction: null,
  aiExplanation: null,
  ...overrides,
});

describe('feedback cluster presentation', () => {
  it('uses product-facing cluster and channel labels', () => {
    expect(clusterStatusLabel('MITIGATING')).toBe('缓解中');
    expect(clusterStatusLabel('UNKNOWN')).toBe('待确认');
    expect(channelLabel('station_feedback')).toBe('学习机');
    expect(feedbackTimeLabel('redblack')).toBe('发布时间');
    expect(metric(null)).toBe('未知');
  });

  it('describes automatic decisions and controlled information', () => {
    expect(decisionLabel(clusterRow({ aiAction: 'LINK_EXISTING' }))).toBe(
      'AI 自动关联',
    );
    expect(decisionLabel(clusterRow({ decisionType: 'NEW_CLUSTER' }))).toBe(
      '规则自动新建',
    );
    expect(confidenceText(0.876)).toBe('88%');
    expect(
      controlledInfoTypes(
        clusterRow({ controlledInfoTypes: 'PHONE,DEVICE_SN,PHONE' }),
      ),
    ).toEqual(['PHONE', 'DEVICE_SN']);
    expect(controlledInfoLabel('DEVICE_SN')).toBe('设备序列号');
  });
});
