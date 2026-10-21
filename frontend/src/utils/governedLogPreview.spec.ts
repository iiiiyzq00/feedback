import { describe, expect, it, vi } from 'vitest'
import {
  GOVERNED_LOG_TRUNCATION_MARKER,
  parseGovernedLogBlob,
} from './governedLogPreview'

describe('governed log preview parser', () => {
  it('preserves UTF-8 characters and lines split across stream chunks', async () => {
    const value = '第一行\n第二行🙂\n最后一行'
    const result = await parseGovernedLogBlob(new Blob([value]), {
      yieldEveryBytes: 4,
      yieldControl: vi.fn().mockResolvedValue(undefined),
    })

    expect(result.lines).toEqual(['第一行', '第二行🙂', '最后一行'])
    expect(result.receivedBytes).toBe(new TextEncoder().encode(value).byteLength)
    expect(result.truncated).toBe(false)
  })

  it('recognizes only the server truncation marker', async () => {
    const result = await parseGovernedLogBlob(new Blob([
      `普通日志提到“截断”但不是合同标记\n${GOVERNED_LOG_TRUNCATION_MARKER}\n仅展示前 5 MB`,
    ]))

    expect(result.truncated).toBe(true)
  })

  it('stops parsing when the governed session is cancelled', async () => {
    const controller = new AbortController()
    controller.abort()

    await expect(parseGovernedLogBlob(new Blob(['sensitive']), { signal: controller.signal }))
      .rejects.toMatchObject({ name: 'AbortError' })
  })
})
