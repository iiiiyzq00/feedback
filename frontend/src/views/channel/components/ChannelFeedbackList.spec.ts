import { describe, expect, it } from 'vitest';
import listComponentSource from './ChannelFeedbackList.vue?raw';
import listModelSource from './useFeedbackList.ts?raw';
import listPresentationSource from './feedbackListPresentation.ts?raw';
import evidenceSource from '@/components/evidence/GovernedEvidenceViewer.vue?raw';
import diagnosticsSource from '@/components/evidence/GovernedDiagnosticsDialog.vue?raw';
import dataTableSource from '@/components/common/DataTable.vue?raw';
import { CHANNEL_COLUMNS } from './channelColumns';

const compact = (source: string) => source.replace(/\s+/g, '');
const listSource = [
  listComponentSource,
  listModelSource,
  listPresentationSource,
].join('\n');

describe('ChannelFeedbackList feedback contract', () => {
  it('keeps its shared table dependency on a named generic row contract', () => {
    expect(dataTableSource).toContain('generic="Row extends object"');
    expect(dataTableSource).toContain('data: Row[]');
    expect(dataTableSource).toContain(
      'rowSelectable?: (row: Row, index: number) => boolean',
    );
    expect(dataTableSource).toContain("'selection-change': [value: Row[]]");
    expect(dataTableSource).toContain("'row-click': [row: Row]");
    expect(dataTableSource).toContain('value: DataTableSortChange');
    expect(dataTableSource).not.toMatch(/\bany\b/);
  });

  it('keeps only keyword, time, and governed export controls', () => {
    expect(listSource).toContain('placeholder="搜索标题、描述或来源编号"');
    expect(listSource).toContain('type="datetimerange"');
    expect(listSource).toContain(
      'hasFeedbackWorkspaceAccess(auth.user, auth.hasPermission)',
    );
    expect(listSource).not.toContain('placeholder="处理状态"');
    expect(listSource).not.toContain('placeholder="负责人"');
    expect(listSource).not.toContain('更多筛选');
  });

  it('supports row click and keeps an explicit fixed detail action', () => {
    expect(listSource).not.toContain('channel-stats');
    expect(listSource).toContain(':selectable="false"');
    expect(listSource).not.toContain('批量分发');
    expect(listSource).not.toContain('批量转派');
    expect(listSource).not.toContain('@click.stop="remind(row)"');
    expect(listSource).not.toContain("openDistribution(row, 'DISTRIBUTE')");
    expect(listSource).toContain('@row-click="openDetail"');
    expect(listComponentSource).toContain('fixed="right"');
    expect(listComponentSource).toContain('label="操作"');
    expect(listComponentSource).toContain('@click.stop="openDetail(row)"');
  });

  it('freezes the learning-machine and Jira feedback columns in order', () => {
    expect(
      CHANNEL_COLUMNS.learning_machine.map((column) => column.label),
    ).toEqual([
      '故障发生时间',
      '问题内容',
      '应用名称',
      '来源平台',
      '关键信息',
      '分析证据',
    ]);
    expect(compact(listSource)).toContain(
      compact('toFeedbackChannelCsv(props.queryChannels)'),
    );
    expect(
      CHANNEL_COLUMNS.jira.map((column) => column.label),
    ).toEqual([
      '故障发生时间',
      '问题内容',
      'Jira 编号',
      'Jira经办人',
      '优先级',
      'Jira解决结果',
      'Jira 更新时间',
      '关键信息',
      '分析证据',
    ]);
  });

  it('removes forbidden quality and red-black fields and preserves required placement', () => {
    const quality = CHANNEL_COLUMNS.quality.map(
      (column) => column.label,
    );
    const redblack = CHANNEL_COLUMNS.redblack.map(
      (column) => column.label,
    );
    expect(quality.indexOf('关键信息')).toBeLessThan(
      quality.indexOf('商返单号'),
    );
    expect(quality).not.toContain('负责人');
    expect(redblack.indexOf('发布时间')).toBeLessThan(
      redblack.indexOf('关键信息'),
    );
    expect(redblack.indexOf('关键信息')).toBeLessThan(
      redblack.indexOf('红黑榜类型'),
    );
    expect(redblack).not.toContain('负责人');
    expect(redblack).not.toContain('评价对象');
  });

  it('shows key-information values directly while logs retain governed preview', () => {
    expect(listSource).not.toContain('show-overflow-tooltip');
    expect(listSource).toContain(
      '<style scoped src="./ChannelFeedbackList.css"></style>',
    );
    expect(listSource).toContain('row.keyDiagnostics');
    expect(listSource).toContain('diagnosticTypeLabel(item.type)');
    expect(listSource).toContain("item.value || '未提供'");
    const compactEvidenceStyle = evidenceSource.match(
      /\.governed-evidence--compact\s*\{([^}]*)\}/s,
    )?.[1];
    expect(compactEvidenceStyle).toContain('flex-wrap: wrap');
    expect(compactEvidenceStyle).not.toContain('max-height');
    expect(compactEvidenceStyle).not.toContain('overflow');
    expect(evidenceSource).not.toContain('overflow-x: auto');
    expect(listSource).not.toContain('class="diagnostic-entry"');
    expect(listSource).toContain('log: row.logCount');
    expect(listSource).toContain('@view-log="openDiagnostics(row, $event)"');
    expect(evidenceSource).toContain('props.counts.log');
    expect(evidenceSource).toContain("key: `log-summary-${index}`");
    expect(listSource).toContain('GovernedEvidenceViewer');
    expect(listSource).not.toContain('请输入本次${label}查验理由');
    expect(evidenceSource).toContain('createObjectUrl(blob)');
    expect(evidenceSource).toContain('<video');
    expect(evidenceSource).not.toContain('canvas.toDataURL');
  });

  it('invalidates late controlled responses and clears sensitive state on unmount', () => {
    expect(diagnosticsSource).toContain(
      'requestGeneration !== generation.value',
    );
    expect(evidenceSource).toContain('requestGeneration !== generation');
    expect(diagnosticsSource).toContain('visible.value = false');
    expect(evidenceSource).toContain('session.cancelAll()');
  });

  it('downloads logs directly and preloads only the bounded thumbnail preview', () => {
    expect(diagnosticsSource).toContain("return '下载日志'");
    expect(diagnosticsSource).toContain('await downloadLogItem(item)');
    expect(diagnosticsSource).toContain('previewBytes: LOG_DETAIL_PREVIEW_BYTES');
    expect(evidenceSource).toContain('previewBytes: LOG_THUMBNAIL_BYTES');
    expect(diagnosticsSource).not.toContain("return '预览日志'");
  });

  it('keeps only four-channel common fields in the aggregate list', () => {
    const all = CHANNEL_COLUMNS.all.map((column) => column.label);
    expect(all).toEqual(['反馈时间', '问题内容', '关键信息', '分析证据']);
    expect(all).not.toContain('来源记录 ID');
    expect(all).not.toContain('负责人');
    expect(all).not.toContain('问题类型');
    expect(all).not.toContain('功能模块');
    expect(listSource).not.toContain('label="同类问题来源"');
  });

  it('does not retain script state for filters removed from the feedback UI', () => {
    for (const field of [
      'modules',
      'problemTypes',
      'reproducibilities',
      'maskingStatus',
      'aiReviewStatus',
      'ownerId',
      'handoffPending',
    ]) {
      expect(listSource).not.toContain(`filters.${field}`);
    }
    expect(listSource).not.toContain('filters.extra');
  });
});
