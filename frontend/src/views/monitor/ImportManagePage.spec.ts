import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getImportBatches: vi.fn(), getImportBatchDetail: vi.fn(), getChannelQuality: vi.fn(),
  getJiraSyncStatus: vi.fn(), enqueueJiraSync: vi.fn(), retryJiraAttachmentTasks: vi.fn(), refreshChannelQuality: vi.fn(),
  rollbackImport: vi.fn(), retryImport: vi.fn(), retryImportTechnicalFailures: vi.fn(),
  downloadImportTemplate: vi.fn(), exportImportReconciliation: vi.fn(), downloadImportErrors: vi.fn(),
}))
const message = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }))
vi.mock('@/api/import', () => api)
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ isAdmin: true }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }))
vi.mock('element-plus', async (original) => ({ ...(await original<typeof import('element-plus')>()), ElMessage: message, ElMessageBox: { confirm: vi.fn() } }))

import ImportManagePage from './ImportManagePage.vue'

describe('ImportManagePage Jira API channel', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset())
    Object.values(message).forEach((fn) => fn.mockReset())
    api.getImportBatches.mockResolvedValue({ records: [], total: 0, page: 1, size: 20 })
    api.getImportBatchDetail.mockResolvedValue(null)
    api.getChannelQuality.mockResolvedValue([])
    api.getJiraSyncStatus.mockResolvedValue({ enabled: true, ready: true, status: 'IDLE', readinessErrors: [], failureCount: 0 })
    api.enqueueJiraSync.mockResolvedValue({ accepted: true, status: { enabled: true, ready: true, status: 'QUEUED', readinessErrors: [], failureCount: 0 } })
    api.retryJiraAttachmentTasks.mockResolvedValue({ retried: 2 })
  })

  it('shows API state while retaining file upload and queues a manual sync', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
    const wrapper = mount(ImportManagePage, { global: {
      plugins: [[VueQueryPlugin, { queryClient: client }]],
      stubs: {
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        ImportBatchList: true, ImportUploadDialog: true, ImportBatchDetail: true,
        StatusBadge: { props: ['status'], template: '<span>{{ status }}</span>' },
        ElCard: { template: '<section><slot name="header"/><slot/></section>' },
        ElDescriptions: { template: '<div><slot/></div>' }, ElDescriptionsItem: { template: '<div><slot/></div>' },
        ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
        ElDropdown: { template: '<div><slot/><slot name="dropdown"/></div>' }, ElDropdownMenu: true, ElDropdownItem: true,
        ElIcon: true, ElTable: true, ElTableColumn: true, ElTabs: true, ElTabPane: true, ElAlert: true, ElDatePicker: true,
      }, directives: { loading: () => undefined },
    } })
    await flushPromises()
    expect(wrapper.text()).toContain('400 Jira API只读同步')
    expect(wrapper.text()).toContain('上传文件')
    await (wrapper.vm as unknown as { syncJiraNow: () => Promise<void> }).syncJiraNow()
    await flushPromises()
    expect(api.enqueueJiraSync).toHaveBeenCalledWith()
    expect(message.success).toHaveBeenCalledWith('Jira同步任务已排队')
    ;(wrapper.vm as unknown as { jiraBackfillRange: [string,string] | null }).jiraBackfillRange = ['2026-08-01T00:00:00','2026-08-02T00:00:00']
    await (wrapper.vm as unknown as { syncJiraBackfill: () => Promise<void> }).syncJiraBackfill()
    expect(api.enqueueJiraSync).toHaveBeenLastCalledWith({ from: '2026-08-01T00:00:00', to: '2026-08-02T00:00:00' })
  })

  it('requeues failed Jira evidence pulls from the operations card', async () => {
    api.getJiraSyncStatus.mockResolvedValue({ enabled: true, ready: true, status: 'IDLE', readinessErrors: [], failureCount: 0, attachmentPendingCount: 0, attachmentFailedCount: 2 })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
    const wrapper = mount(ImportManagePage, { global: { plugins: [[VueQueryPlugin, { queryClient: client }]], stubs: {
      PageHeader: true, ImportBatchList: true, ImportUploadDialog: true, ImportBatchDetail: true, StatusBadge: true,
      ElCard: { template: '<section><slot name="header"/><slot/></section>' }, ElDescriptions: { template: '<div><slot/></div>' },
      ElDescriptionsItem: { template: '<div><slot/></div>' }, ElButton: { template: '<button><slot/></button>' }, ElDropdown: true,
      ElTable: true, ElTableColumn: true, ElTabs: true, ElTabPane: true, ElAlert: true, ElDatePicker: true,
    }, directives: { loading: () => undefined } } })
    await flushPromises()
    await (wrapper.vm as unknown as { retryJiraEvidence: () => Promise<void> }).retryJiraEvidence()
    expect(api.retryJiraAttachmentTasks).toHaveBeenCalledOnce()
    expect(message.success).toHaveBeenCalledWith('已重新排队 2 条证据拉取任务')
  })
})
