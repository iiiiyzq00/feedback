import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClusterEvaluationPage from './ClusterEvaluationPage.vue'

const latest = vi.fn()
vi.mock('@/api/admin', () => ({
  getLatestClusterEvaluation: () => latest(),
  submitClusterEvaluation: vi.fn(),
  exportLatestClusterEvaluation: vi.fn(),
}))

const passthrough = { template: '<div><slot name="header"/><slot/></div>' }

describe('ClusterEvaluationPage', () => {
  beforeEach(() => latest.mockResolvedValue({ status: 'COMPLETED', model: 'deepseek-v4-pro', completedAt: '2026-08-03', summary: {
    gate: 'PASSED', sampleCount: 100, f1: 0.92, precision: 0.93, recall: 0.91, errorMergeRate: 0.02,
    clusterMetricsAvailable: true, purity: 0.9, falseSplitRate: 0.03, unclusteredRate: 0.04,
    thresholds: { f1Min: 0.75, purityMin: 0.8 }, channelSlices: { SDK: { count: 25, accuracy: 0.9 } },
    clusterSummaries: [{ clusterId: 'c1', sampleCount: 20, dominantExpectedClusterId: 'g1', purity: 0.9, channels: ['SDK'], anomalyCaseIds: ['case-9'] }],
    errorCases: [{ caseId: 'case-9', channel: 'SDK', predictedClusterId: 'c1', expectedClusterId: 'g2', similarity: 0.8, evidence: '边界样本' }],
  } }))
  it('展示簇级指标、阈值、渠道切片和异常证据', async () => {
    const wrapper=mount(ClusterEvaluationPage,{global:{directives:{loading:{}},stubs:{ElCard:passthrough,ElAlert:true,ElEmpty:true,ElDescriptions:passthrough,
      ElDescriptionsItem:{props:['label'],template:'<div>{{label}}<slot/></div>'},ElTable:passthrough,ElTableColumn:true,ElButton:passthrough}}})
    await flushPromises()
    expect(wrapper.text()).toContain('簇纯度')
    expect(wrapper.text()).toContain('单簇质量与异常样本')
    expect(wrapper.text()).toContain('错误与人工推翻样本')
    expect(wrapper.find('[data-testid="threshold-table"]').exists()).toBe(true)
  })
})
