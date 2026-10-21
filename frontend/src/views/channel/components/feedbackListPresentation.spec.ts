import { describe, expect, it } from 'vitest';
import {
  channelMetaValue,
  formatColumnValue,
  formatInlineTime,
  isTextColumn,
  isTimeColumn,
  issueCategoryName,
  rowValue,
} from './feedbackListPresentation';
import type { FeedbackListRow } from '@/api/feedback';

describe('feedback feedback list presentation', () => {
  it('formats governed list fields without generic object casts', () => {
    const row: FeedbackListRow = {
      id: 1,
      sourceChannel: 'jira',
      status: 'NEW',
      reportedAt: null,
      title: '无法登录',
      channelMeta: { jira_key: 'FB-7' },
    };
    expect(rowValue(row, 'title')).toBe('无法登录');
    expect(channelMetaValue(row, 'jira_key')).toBe('FB-7');
    expect(issueCategoryName('BUG')).toBe('故障');
  });

  it('preserves source value semantics', () => {
    expect(
      formatColumnValue({ prop: 'listType', label: '类型' }, 'BLACK'),
    ).toBe('黑榜');
    expect(
      formatColumnValue({ prop: 'exchangeRequest', label: '换机' }, 1),
    ).toBe('是');
    expect(formatInlineTime('invalid')).toBe('-');
    expect(isTextColumn('description')).toBe(true);
    expect(isTimeColumn('sourceUpdatedAt')).toBe(true);
  });
});
