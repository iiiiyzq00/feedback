import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  authorizeFeedbackAttachment: vi.fn(),
  authorizeOriginalLogDownload: vi.fn(),
  streamAuthorizedFeedbackAttachment: vi.fn(),
  viewFeedbackDiagnostics: vi.fn(),
}))

vi.mock('@/api/feedback', () => api)

import GovernedDiagnosticsDialog from './GovernedDiagnosticsDialog.vue'

function mountDialog() {
  return mount(GovernedDiagnosticsDialog, {
    global: {
      directives: { loading: () => undefined },
      stubs: {
        'el-dialog': {
          props: ['modelValue'],
          template: '<section v-if="modelValue" class="dialog"><slot name="header"/><slot /></section>',
        },
        'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
        'el-tag': { template: '<span><slot /></span>' },
        'el-button': { template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
        'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
      },
    },
  })
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
})

describe('GovernedDiagnosticsDialog', () => {
  it('downloads the bounded product-role preview without opening a dialog', async () => {
    const item = {
      type: 'APPLICATION_LOG', source: 'GOVERNED_ATTACHMENT', attachmentId: 2,
      fileSizeBytes: 1024, mimeType: 'application/gzip', available: true,
    }
    api.authorizeFeedbackAttachment.mockResolvedValue({ downloadUrl: '/stream', delivery: 'ONE_TIME_SECURE_STREAM' })
    api.streamAuthorizedFeedbackAttachment.mockResolvedValue(new Blob(['line 1\nline 2']))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const wrapper = mountDialog()
    const initial = {
      sourceId: 1, contentVisibility: 'PARTIALLY_HIDDEN' as const, responseCacheable: false as const,
      usageRestriction: '页面关闭后清除', diagnostics: [item],
    }
    await (wrapper.vm as unknown as { openLog: (id: number, log: typeof item, response: typeof initial) => Promise<void> }).openLog(1, item, initial)
    await flushPromises()
    expect(wrapper.findAll('.dialog')).toHaveLength(0)
    expect(api.authorizeOriginalLogDownload).not.toHaveBeenCalled()
    expect(api.streamAuthorizedFeedbackAttachment).toHaveBeenCalledWith(
      '/stream',
      expect.objectContaining({ previewBytes: 512 * 1024 }),
    )
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalled()
  })

  it('offers RAW roles a reason-free purpose-isolated original download', async () => {
    const item = {
      type: 'APPLICATION_LOG', source: 'GOVERNED_ATTACHMENT', attachmentId: 2,
      fileSizeBytes: 1024, mimeType: 'application/gzip', available: true,
    }
    api.authorizeFeedbackAttachment.mockResolvedValue({ downloadUrl: '/stream', delivery: 'ONE_TIME_SECURE_STREAM' })
    api.authorizeOriginalLogDownload.mockResolvedValue({ downloadUrl: '/original', delivery: 'ONE_TIME_ORIGINAL_DOWNLOAD' })
    api.streamAuthorizedFeedbackAttachment.mockResolvedValue(new Blob(['line 1'], { type: 'text/plain' }))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:original')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const initial = {
      sourceId: 1, contentVisibility: 'RAW', responseCacheable: false as const,
      usageRestriction: '页面关闭后清除', diagnostics: [item],
    }
    const wrapper = mountDialog()
    await (wrapper.vm as unknown as { openLog: (id: number, log: typeof item, response: typeof initial) => Promise<void> }).openLog(1, item, initial)
    await flushPromises()
    expect(wrapper.findAll('.dialog')).toHaveLength(0)
    expect(api.authorizeOriginalLogDownload).toHaveBeenCalledWith(1, 2, expect.any(AbortSignal))
    expect(api.streamAuthorizedFeedbackAttachment).toHaveBeenCalledWith('/original', expect.objectContaining({ timeoutMs: 0 }))
  })

  it('deduplicates repeated direct downloads and never renders a log dialog', async () => {
    const item = {
      type: 'APPLICATION_LOG', source: 'GOVERNED_ATTACHMENT', attachmentId: 2,
      fileSizeBytes: 1024, mimeType: 'application/gzip', available: true,
    }
    let resolveDiagnostics!: (value: unknown) => void
    api.viewFeedbackDiagnostics.mockReturnValue(new Promise((resolve) => { resolveDiagnostics = resolve }))
    api.authorizeFeedbackAttachment.mockResolvedValue({ downloadUrl: '/stream', delivery: 'ONE_TIME_SECURE_STREAM' })
    api.streamAuthorizedFeedbackAttachment.mockResolvedValue(new Blob(['line 1'], { type: 'text/plain' }))
    api.authorizeOriginalLogDownload.mockResolvedValue({ downloadUrl: '/original', delivery: 'ONE_TIME_ORIGINAL_DOWNLOAD' })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:original')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const wrapper = mountDialog()
    const dialog = wrapper.vm as unknown as { openLogDirect: (id: number, log: typeof item) => Promise<void> }
    const first = dialog.openLogDirect(1, item)
    const second = dialog.openLogDirect(1, item)
    expect(api.viewFeedbackDiagnostics).toHaveBeenCalledTimes(1)
    resolveDiagnostics({
      sourceId: 1, contentVisibility: 'RAW', responseCacheable: false,
      usageRestriction: '页面关闭后清除', diagnostics: [item],
    })
    await Promise.all([first, second])
    await flushPromises()
    expect(api.authorizeOriginalLogDownload).toHaveBeenCalledTimes(1)
    expect(api.streamAuthorizedFeedbackAttachment).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.dialog')).toHaveLength(0)
  })

  it('aborts an in-flight diagnostics request when the component is destroyed', async () => {
    let requestSignal: AbortSignal | undefined
    api.viewFeedbackDiagnostics.mockImplementation((_id: number, signal: AbortSignal) => {
      requestSignal = signal
      return new Promise(() => undefined)
    })
    const wrapper = mountDialog()
    void (wrapper.vm as unknown as { open: (id: number) => Promise<void> }).open(1)
    await flushPromises()
    wrapper.unmount()
    expect(requestSignal?.aborted).toBe(true)
  })
})
