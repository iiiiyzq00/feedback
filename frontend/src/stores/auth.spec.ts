import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/auth', () => ({
  login: vi.fn(async () => ({ user: { id: 1, username: 'admin', realName: 'A', role: 'ADMIN', permissions: ['admin:manage'] } })),
  logout: vi.fn(async () => undefined),
  getUserInfo: vi.fn(),
  exchangeFeishuLogin: vi.fn(async () => ({ user: { id: 2, username: 'feishu_user', realName: '测试人员', role: 'ADMIN', permissions: ['admin:manage'] }, returnPath: '/monitor/overview' })),
}))

import { useAuthStore } from './auth'

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })
  it('keeps the authenticated user in memory and clears it on logout', async () => {
    const auth=useAuthStore(); await auth.login('admin','password')
    expect(auth.isAdmin).toBe(true); expect(auth.isLoggedIn).toBe(true)
    await auth.logout(); expect(auth.user).toBeNull(); expect(auth.isLoggedIn).toBe(false)
  })

  it('finishes local logout even when the server logout request fails', async () => {
    const api = await import('@/api/auth')
    vi.mocked(api.logout).mockRejectedValueOnce(new Error('network unavailable'))
    const auth=useAuthStore(); await auth.login('admin','password')
    await expect(auth.logout()).resolves.toBeUndefined()
    expect(auth.user).toBeNull(); expect(auth.isLoggedIn).toBe(false)
  })

  it('evaluates permissions from the current user without role-based bypasses', () => {
    const auth=useAuthStore()
    expect(auth.hasPermission('access:user_management')).toBe(false)
    auth.setUser({id:2,username:'auditor',realName:'Auditor',role:'AUDITOR',permissions:['access:audit_logs']})
    expect(auth.hasPermission('access:audit_logs')).toBe(true)
    expect(auth.hasPermission('access:user_management')).toBe(false)
  })

  it('exchanges a one-time Feishu code without persisting a browser token', async () => {
    const auth=useAuthStore()
    const returnPath=await auth.loginWithFeishuCode('one-time-code')
    expect(returnPath).toBe('/monitor/overview')
    expect(auth.user?.role).toBe('ADMIN')
  })
})
