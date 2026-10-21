import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import componentSource from './GovernedEvidenceViewer.vue?raw'

const api = vi.hoisted(() => ({
  authorizeFeedbackAttachment: vi.fn(),
  authorizeFeedbackAttachmentPreview: vi.fn(),
  authorizeFeedbackAttachmentPreviews: vi.fn(),
  getFeedbackAttachments: vi.fn(),
  streamAuthorizedFeedbackAttachment: vi.fn(),
}))
vi.mock('@/api/feedback', () => api)
import GovernedEvidenceViewer from './GovernedEvidenceViewer.vue'

const wrappers: VueWrapper[] = []
let intersectionCallback: IntersectionObserverCallback

function mountViewer(props: Record<string, unknown>) {
  const wrapper = mount(GovernedEvidenceViewer, {
    props: props as never,
    global: {
      directives: { loading: () => undefined },
      stubs: {
        'el-dialog': { props: ['modelValue'], template: '<section v-if="modelValue" class="dialog"><slot name="header"/><slot /></section>' },
        'el-result': { props: ['title', 'subTitle'], template: '<div>{{ title }}{{ subTitle }}<slot name="extra" /></div>' },
        'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  api.authorizeFeedbackAttachment.mockResolvedValue({
    grantId: 'once', expiresAt: '2026-09-03T12:00:00', expiresInSeconds: 60,
    delivery: 'ONE_TIME_SECURE_STREAM', downloadUrl: '/api/v1/feedback/1/attachments/2/stream?grantId=once',
  })
  api.authorizeFeedbackAttachmentPreview.mockResolvedValue({
    grantId: 'preview-once', expiresAt: '2026-09-03T12:00:00', expiresInSeconds: 60,
    delivery: 'ONE_TIME_DERIVED_PREVIEW', downloadUrl: '/api/v1/feedback/1/attachments/2/preview?grantId=preview-once',
  })
  api.authorizeFeedbackAttachmentPreviews.mockImplementation((_sourceId: number, attachmentIds: number[]) => Promise.resolve({
    items: attachmentIds.map(attachmentId => ({
      attachmentId,
      status: 'AVAILABLE',
      grant: {
        grantId: 'preview-once', expiresAt: '2026-09-03T12:00:00', expiresInSeconds: 60,
        delivery: 'ONE_TIME_DERIVED_PREVIEW', downloadUrl: `/api/v1/feedback/1/attachments/${attachmentId}/preview?grantId=preview-once`,
      },
    })),
  }))
  api.streamAuthorizedFeedbackAttachment.mockImplementation((url: string) => Promise.resolve(
    new Blob(['image'], { type: url.includes('/preview') ? 'image/jpeg' : 'image/png' }),
  ))
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) { intersectionCallback = callback }
    observe() {} unobserve() {} disconnect() {}
  })
  vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue({ width: 800, height: 600, close: vi.fn() }))
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => callback(new Blob(['thumb'], { type: 'image/jpeg' })))
  vi.spyOn(URL, 'createObjectURL').mockReturnValueOnce('blob:content').mockReturnValue('blob:thumbnail')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
})

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.clearAllMocks()
})

describe('GovernedEvidenceViewer', () => {
  it('teleports the media dialog outside clickable table rows', () => {
    expect(componentSource).toContain('append-to-body')
  })

  it('renders every image, video and log as an independent thumbnail without a log icon', async () => {
    const wrapper = mountViewer({
      sourceId: 1,
      attachments: [
        { id: 1, attachmentType: 'IMAGE', mimeType: 'image/png', available: true, previewStatus: 'AVAILABLE' },
        { id: 2, attachmentType: 'VIDEO', mimeType: 'video/mp4', available: true, previewStatus: 'AVAILABLE' },
      ],
      logAttachments: [
        { type: 'APPLICATION_LOG', source: 'GOVERNED_ATTACHMENT', attachmentId: 3, available: true },
        { type: 'APPLICATION_LOG', source: 'GOVERNED_ATTACHMENT', attachmentId: 4, available: true },
      ],
      mode: 'detail',
    })
    await flushPromises()
    expect(wrapper.findAll('.evidence-thumbnail')).toHaveLength(4)
    expect(wrapper.text()).toContain('日志预览生成中')
    expect(wrapper.findAll('.evidence-thumbnail__log')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('▤')
    expect(wrapper.text()).not.toMatch(/个|MB|image\/|video\//)
  })

  it('loads the bounded cached log preview when its thumbnail enters the viewport', async () => {
    api.streamAuthorizedFeedbackAttachment.mockResolvedValue(new Blob(['line 1\nline 2\nline 3'], { type: 'text/plain' }))
    api.getFeedbackAttachments.mockResolvedValue([{
      id: 9,
      attachmentType: 'LOG',
      mimeType: 'text/plain',
      fileSizeBytes: 100_000,
      available: true,
    }])
    const wrapper = mountViewer({ sourceId: 1, counts: { log: 1 } })
    await flushPromises()
    const target = wrapper.get('.evidence-thumbnail--log').element
    intersectionCallback([{ isIntersecting: true, target } as IntersectionObserverEntry], {} as IntersectionObserver)
    await flushPromises()

    expect(api.authorizeFeedbackAttachment).toHaveBeenCalledWith(1, 9, expect.any(AbortSignal))
    expect(api.streamAuthorizedFeedbackAttachment).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ previewBytes: 4 * 1024 }),
    )
    await vi.waitFor(() => expect(wrapper.get('.evidence-thumbnail__log').text()).toContain('line 1'))
    expect(wrapper.get('.evidence-thumbnail__log').text()).toContain('line 2')
  })

  it('keeps a not-yet-generated log preview pending instead of reporting a load failure', async () => {
    api.streamAuthorizedFeedbackAttachment.mockRejectedValue({ response: { status: 409 } })
    api.getFeedbackAttachments.mockResolvedValue([{
      id: 10,
      attachmentType: 'LOG',
      mimeType: 'application/gzip',
      available: true,
    }])
    const wrapper = mountViewer({ sourceId: 1, counts: { log: 1 } })
    await flushPromises()
    const target = wrapper.get('.evidence-thumbnail--log').element
    intersectionCallback([{ isIntersecting: true, target } as IntersectionObserverEntry], {} as IntersectionObserver)

    await vi.waitFor(() => expect(wrapper.get('.evidence-thumbnail__log').text()).toBe('日志预览生成中…'))
    expect(wrapper.text()).not.toContain('日志加载失败')
  })

  it('merges a diagnostic and attachment for the same log into one preloadable thumbnail', async () => {
    api.streamAuthorizedFeedbackAttachment.mockResolvedValue(new Blob(['cached preview'], { type: 'text/plain' }))
    const wrapper = mountViewer({
      sourceId: 1,
      attachments: [{ id: 7, attachmentType: 'LOG', mimeType: 'application/gzip', available: true }],
      logAttachments: [{ type: 'APPLICATION_LOG', source: 'GOVERNED_ATTACHMENT', attachmentId: 7, available: true }],
    })
    await flushPromises()
    expect(wrapper.findAll('.evidence-thumbnail--log')).toHaveLength(1)
    const target = wrapper.get('.evidence-thumbnail--log').element
    intersectionCallback([{ isIntersecting: true, target } as IntersectionObserverEntry], {} as IntersectionObserver)
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.get('.evidence-thumbnail__log').text()).toContain('cached preview'))
  })

  it('delegates exactly the selected log for direct download', async () => {
    const log = { type: 'APPLICATION_LOG', source: 'GOVERNED_ATTACHMENT', attachmentId: 7, available: true }
    const wrapper = mountViewer({ sourceId: 1, attachments: [], logAttachments: [log] })
    await flushPromises()
    await wrapper.get('.evidence-thumbnail--log').trigger('click')
    expect(wrapper.emitted('view-log')).toEqual([[log]])
    expect(wrapper.find('.dialog').exists()).toBe(false)
  })

  it('renders every count-only log in a list and delegates governed lookup to its parent', async () => {
    api.getFeedbackAttachments.mockResolvedValue([])
    const wrapper = mountViewer({
      sourceId: 1,
      counts: { log: 3 },
    })
    await flushPromises()

    expect(wrapper.findAll('.evidence-thumbnail--log')).toHaveLength(3)
    await wrapper.get('.evidence-thumbnail--log').trigger('click')
    expect(wrapper.emitted('view-log')).toEqual([[
      {
        type: 'APPLICATION_LOG',
        source: 'GOVERNED_ATTACHMENT',
        available: true,
      },
    ]])
  })

  it('does not request attachment summaries when every declared evidence count is zero', async () => {
    const wrapper = mountViewer({
      sourceId: 29478,
      counts: { attachment: 0, image: 0, video: 0, log: 0 },
    })
    await flushPromises()

    expect(api.getFeedbackAttachments).not.toHaveBeenCalled()
    expect(wrapper.find('.evidence-thumbnail').exists()).toBe(false)
  })

  it('still requests attachment summaries when evidence counts are unknown', async () => {
    api.getFeedbackAttachments.mockResolvedValue([])
    mountViewer({ sourceId: 32623 })
    await flushPromises()

    expect(api.getFeedbackAttachments).toHaveBeenCalledWith(32623, expect.any(AbortSignal))
  })

  it('does not load media before its own element enters the viewport', async () => {
    const wrapper = mountViewer({ sourceId: 1, attachments: [{ id: 2, attachmentType: 'IMAGE', mimeType: 'image/png', available: true, previewStatus: 'AVAILABLE' }] })
    await flushPromises()
    expect(api.authorizeFeedbackAttachmentPreviews).not.toHaveBeenCalled()
    const target = wrapper.get('.evidence-thumbnail').element
    intersectionCallback([{ isIntersecting: true, target } as IntersectionObserverEntry], {} as IntersectionObserver)
    await flushPromises()
    expect(api.authorizeFeedbackAttachmentPreviews).toHaveBeenCalledTimes(1)
    expect(api.authorizeFeedbackAttachment).not.toHaveBeenCalled()
    expect(wrapper.get('img').attributes('src')).toBe('blob:content')
  })

  it('coalesces several visible media into one bounded authorization request', async () => {
    const attachments = [
      { id: 2, attachmentType: 'VIDEO', mimeType: 'video/mp4', available: true, previewStatus: 'AVAILABLE' },
      { id: 3, attachmentType: 'VIDEO', mimeType: 'video/mp4', available: true, previewStatus: 'AVAILABLE' },
    ]
    const wrapper = mountViewer({ sourceId: 1, attachments })
    await flushPromises()
    const targets = wrapper.findAll('.evidence-thumbnail').map(node => ({
      isIntersecting: true,
      target: node.element,
    } as IntersectionObserverEntry))

    intersectionCallback(targets, {} as IntersectionObserver)
    await flushPromises()

    expect(api.authorizeFeedbackAttachmentPreviews).toHaveBeenCalledTimes(1)
    expect(api.authorizeFeedbackAttachmentPreviews).toHaveBeenCalledWith(1, [2, 3])
    expect(api.streamAuthorizedFeedbackAttachment).toHaveBeenCalledTimes(2)
  })

  it('preserves an already loaded preview when attachment props refresh without changing source', async () => {
    const attachment = { id: 2, attachmentType: 'IMAGE', mimeType: 'image/png', available: true, previewStatus: 'AVAILABLE' as const }
    const wrapper = mountViewer({ sourceId: 1, attachments: [attachment] })
    await flushPromises()
    intersectionCallback([{
      isIntersecting: true,
      target: wrapper.get('.evidence-thumbnail').element,
    } as IntersectionObserverEntry], {} as IntersectionObserver)
    await flushPromises()
    const originalUrl = wrapper.get('img').attributes('src')

    await wrapper.setProps({ attachments: [{ ...attachment }] })
    await flushPromises()

    expect(wrapper.get('img').attributes('src')).toBe(originalUrl)
    expect(api.authorizeFeedbackAttachmentPreviews).toHaveBeenCalledTimes(1)
    expect(URL.revokeObjectURL).not.toHaveBeenCalledWith(originalUrl)
  })

  it('rejects MIME spoofing before creating a browser URL', async () => {
    api.streamAuthorizedFeedbackAttachment.mockResolvedValue(new Blob(['<html>'], { type: 'text/html' }))
    const wrapper = mountViewer({ sourceId: 1, attachments: [{ id: 2, attachmentType: 'IMAGE', mimeType: 'image/png', available: true, previewStatus: 'AVAILABLE' }] })
    await flushPromises()
    const target = wrapper.get('.evidence-thumbnail').element
    intersectionCallback([{ isIntersecting: true, target } as IntersectionObserverEntry], {} as IntersectionObserver)
    await flushPromises()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    expect(wrapper.get('.evidence-thumbnail').classes()).toContain('is-unsupported')
  })

  it('loads server-derived previews automatically and keeps originals click-only', () => {
    expect(componentSource).toContain('requestGovernedPreviewAuthorization')
    expect(componentSource).not.toContain('createImageBitmap')
    expect(componentSource).not.toContain('requestVideoFrameCallback')
    expect(componentSource).toContain('const contentLoads = new Map<string, Promise<Blob>>()')
    expect(componentSource).toContain('const existing = contentLoads.get(item.key)')
    expect(componentSource).toContain('await ensureContent(item)')
    expect(componentSource).toContain('session.cancelAll()')
    expect(componentSource).toContain('observer?.disconnect()')
    expect(componentSource).toContain("return ['IMAGE', 'VIDEO', 'LOG'].includes(item.kind)")
  })

  it('loads the original independently when a derived preview is still loading', async () => {
    let resolveStream!: (blob: Blob) => void
    const previewStream = new Promise<Blob>((resolve) => { resolveStream = resolve })
    api.streamAuthorizedFeedbackAttachment.mockImplementation((url: string) =>
      url.includes('/preview') ? previewStream : Promise.resolve(new Blob(['image'], { type: 'image/png' })),
    )
    const wrapper = mountViewer({
      sourceId: 1,
      attachments: [{ id: 2, attachmentType: 'IMAGE', mimeType: 'image/png', available: true, previewStatus: 'AVAILABLE' }],
    })
    await flushPromises()
    const thumbnail = wrapper.get('.evidence-thumbnail')
    intersectionCallback([{ isIntersecting: true, target: thumbnail.element } as IntersectionObserverEntry], {} as IntersectionObserver)
    await flushPromises()

    await thumbnail.trigger('click')

    expect(wrapper.find('.dialog .media-dialog img').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('证据暂不可查看')
    expect(api.authorizeFeedbackAttachmentPreviews).toHaveBeenCalledTimes(1)
    expect(api.authorizeFeedbackAttachment).toHaveBeenCalledTimes(1)
    expect(api.streamAuthorizedFeedbackAttachment).toHaveBeenCalledTimes(2)

    resolveStream(new Blob(['preview'], { type: 'image/jpeg' }))
    await flushPromises()
    expect(api.authorizeFeedbackAttachment).toHaveBeenCalledTimes(1)
  })
})
