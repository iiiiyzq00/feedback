import { describe, expect, it, vi } from 'vitest'
import requestSource from './request.ts?raw'
import axios from 'axios'
import { advanceAuthenticationGeneration, browserCsrfToken, clearRequestCache, csrfRetryToken, isCurrentAuthenticationGeneration } from './request'

describe('authentication request boundaries', () => {
  it('never reads a JWT from browser storage or creates an authorization header', () => {
    expect(requestSource).not.toContain("localStorage.getItem('token')")
    expect(requestSource).not.toContain('headers.Authorization')
    expect(requestSource).toContain("xsrfCookieName: 'XSRF-TOKEN'")
  })

  it('does not let a delayed 401 from the old session erase a newly issued token', () => {
    expect(isCurrentAuthenticationGeneration(0)).toBe(true)
    advanceAuthenticationGeneration()
    expect(isCurrentAuthenticationGeneration(0)).toBe(false)
    expect(isCurrentAuthenticationGeneration(1)).toBe(true)
  })

  it('exposes cache clearing for account/session boundaries', () => {
    expect(clearRequestCache).toBeTypeOf('function')
  })

  it('retries an unsafe request once when Spring has just issued the missing CSRF cookie', () => {
    expect(csrfRetryToken(403, { method: 'post' }, 'theme=dark; XSRF-TOKEN=csrf-123')).toBe('csrf-123')
    expect(csrfRetryToken(403, { method: 'post', __csrfRetried: true }, 'XSRF-TOKEN=csrf-123')).toBeUndefined()
    expect(csrfRetryToken(403, { method: 'get' }, 'XSRF-TOKEN=csrf-123')).toBeUndefined()
    expect(csrfRetryToken(403, { method: 'post', headers: { 'X-XSRF-TOKEN': 'existing' } }, 'XSRF-TOKEN=csrf-123')).toBeUndefined()
  })

  it('coalesces CSRF bootstrap and uses its same-origin token response when no cookie exists', async () => {
    const get = vi.spyOn(axios, 'get').mockResolvedValue({ data: { data: { token: 'bootstrapped-token' } } })

    const [first, second] = await Promise.all([browserCsrfToken(''), browserCsrfToken('')])

    expect(first).toBe('bootstrapped-token')
    expect(second).toBe('bootstrapped-token')
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/api/v1/auth/csrf', expect.objectContaining({ timeout: 10_000 }))
    get.mockRestore()
  })
})
