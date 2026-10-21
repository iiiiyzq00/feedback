import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ImportBatchDetail from './ImportBatchDetail.vue'

describe('ImportBatchDetail', () => {
  it('详情失败时允许原位重试', async () => {
    const wrapper = mount(ImportBatchDetail, {
      props: { visible: true, detail: null, loading: false, error: '网络暂时不可用', isAdmin: false },
      global: { stubs: {
        ElDrawer: { template: '<section><slot/></section>' },
        StateResult: { template: '<button @click="$emit(\'retry\')">重新加载</button>' },
        ElDescriptions: true, ElDescriptionsItem: true, ElTable: true, ElTableColumn: true, ElButton: true,
        ElTag: true, ElAlert: true, ElTooltip: true,
      }, directives: { loading: () => undefined } },
    })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('运营角色查看 SDK 失败批次时显示有原因的禁用重试按钮', () => {
    const wrapper = mount(ImportBatchDetail, {
      props: {
        visible: true,
        loading: false,
        error: '',
        isAdmin: false,
        detail: {
          batchId: 'sdk-failed', channel: 'SDK', status: 'FAILED', totalRows: 1,
          successRows: 0, failedRows: 0, skippedRows: 0, reviewedRows: 0,
          createdAt: '2026-08-03T13:00:00', progressPercent: 0, canRetry: true,
          lastErrorCode: 'SQLException',
        },
      },
      global: { stubs: {
        ElDrawer: { template: '<section><slot/></section>' },
        ElDescriptions: { template: '<div><slot/></div>' },
        ElDescriptionsItem: { template: '<div><slot/></div>' },
        ElTable: true, ElTableColumn: true, StatusBadge: true, ElAlert: true, ElTag: true,
        ElTooltip: { props: ['content'], template: '<div class="tooltip" :data-content="content"><slot/></div>' },
        ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
      }, directives: { loading: () => undefined } },
    })
    const retry = wrapper.get('button')
    expect(retry.attributes('disabled')).toBeDefined()
    expect(wrapper.get('.tooltip').attributes('data-content')).toBe('SDK 模拟导入仅允许管理员重试')
  })
})
