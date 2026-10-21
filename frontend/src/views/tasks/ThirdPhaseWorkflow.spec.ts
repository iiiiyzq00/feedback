import { describe, expect, it } from 'vitest';
import requestSource from './ProductFeedbackPage.vue?raw';
import detailSource from '../channel/FeedbackDetailPage.vue?raw';
import listSource from '../channel/components/ChannelFeedbackList.vue?raw';
import listModelSource from '../channel/components/useFeedbackList.ts?raw';
import routerSource from '@/router/index.ts?raw';

describe('third phase workflow contract', () => {
  it('keeps deleted assignment workflow out of the feedback detail', () => {
    expect(detailSource).not.toContain('处理结论');
    expect(detailSource).not.toContain('重新打开');
    expect(detailSource).not.toContain('openAssignment(');
    expect(detailSource).not.toContain('提出需求');
  });

  it('supports product feedback capture and permission-governed list export', () => {
    for (const label of [
      '字段缺失',
      '展示问题',
      '流程问题',
      '权限问题',
      '数据异常',
      '新需求',
    ])
      expect(requestSource).toContain(label);
    expect(requestSource).toContain('导出评审清单');
    expect(requestSource).toContain('不上传文件正文');
    expect(listModelSource).toContain(
      'hasFeedbackWorkspaceAccess(auth.user, auth.hasPermission)',
    );
    expect(listSource).not.toContain('保存演示视图');
    expect(routerSource).not.toContain('path: "tasks/my"');
    expect(routerSource).toContain('path: "tasks/product-feedback"');
  });
});
