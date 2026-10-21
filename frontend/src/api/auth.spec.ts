import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./request', () => ({
  get: vi.fn(),
  post: vi.fn(),
}))

import { post } from './request'
import { beginFeishuLogin } from './auth'

const postMock = vi.mocked(post)

describe('Feishu authorization bootstrap', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    postMock.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('retries a transient gateway failure and returns the successful authorization URL', async () => {
    postMock
      .mockRejectedValueOnce({ response: { status: 502 } })
      .mockRejectedValueOnce({ response: { status: 503 } })
      .mockResolvedValueOnce({ url: 'https://open.feishu.cn/authorize' })

    const resultPromise = beginFeishuLogin('/')
    await vi.runAllTimersAsync()

    await expect(resultPromise).resolves.toEqual({ url: 'https://open.feishu.cn/authorize' })
    expect(postMock).toHaveBeenCalledTimes(3)
    expect(postMock).toHaveBeenLastCalledWith(
      '/auth/feishu/authorize?returnPath=%2F',
      undefined,
      { silent: true },
    )
  })

  it('does not retry a client or configuration error', async () => {
    const error = { response: { status: 400 } }
    postMock.mockRejectedValueOnce(error)

    await expect(beginFeishuLogin('/channel/all')).rejects.toBe(error)
    expect(postMock).toHaveBeenCalledTimes(1)
  })

  it('does not retry an ambiguous network failure that may have reached the OAuth state store', async () => {
    const error = new Error('Network Error')
    postMock.mockRejectedValueOnce(error)

    await expect(beginFeishuLogin('/')).rejects.toBe(error)
    expect(postMock).toHaveBeenCalledTimes(1)
  })

  it('stops after the bounded number of transient retries', async () => {
    const error = { response: { status: 504 } }
    postMock.mockRejectedValue(error)

    const resultPromise = beginFeishuLogin('/')
    const rejection = expect(resultPromise).rejects.toBe(error)
    await vi.runAllTimersAsync()

    await rejection
    expect(postMock).toHaveBeenCalledTimes(3)
  })
})
