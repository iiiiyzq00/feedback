import { afterEach, describe, expect, it, vi } from 'vitest'
import { openAuthorizedExternalWindow } from './externalWindow'

describe('openAuthorizedExternalWindow', () => {
  afterEach(() => vi.restoreAllMocks())

  it('opens synchronously before awaiting the authorized URL', async () => {
    let resolveUrl!: (value: string) => void
    const url = new Promise<string>(resolve => { resolveUrl = resolve })
    const replace = vi.fn(), close = vi.fn()
    const popup = { opener: window, location: { replace }, close } as unknown as Window
    const open = vi.spyOn(window, 'open').mockReturnValue(popup)

    const result = openAuthorizedExternalWindow(() => url)
    expect(open).toHaveBeenCalledWith('about:blank', '_blank')
    expect((popup as Window).opener).toBeNull()
    expect(replace).not.toHaveBeenCalled()
    resolveUrl('https://jira.example.com/browse/SPFB-1')

    await expect(result).resolves.toBe('OPENED')
    expect(replace).toHaveBeenCalledWith('https://jira.example.com/browse/SPFB-1')
    expect(close).not.toHaveBeenCalled()
  })

  it('reports a blocked popup without requesting the URL', async () => {
    vi.spyOn(window, 'open').mockReturnValue(null)
    const load = vi.fn()
    await expect(openAuthorizedExternalWindow(load)).resolves.toBe('BLOCKED')
    expect(load).not.toHaveBeenCalled()
  })

  it('closes the placeholder tab when authorization fails', async () => {
    const close = vi.fn()
    vi.spyOn(window, 'open').mockReturnValue({ opener: window, location: { replace: vi.fn() }, close } as unknown as Window)
    await expect(openAuthorizedExternalWindow(async () => { throw new Error('forbidden') })).rejects.toThrow('forbidden')
    expect(close).toHaveBeenCalledOnce()
  })
})
