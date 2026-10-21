import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'

const auth = vi.hoisted(() => ({
  user: null as null | { role: string, roles?: string[], authProvider?: string, workspaceAccessRelaxed?: boolean },
  loadCurrentUser: vi.fn(),
  logout: vi.fn(),
  hasPermission: vi.fn(),
}))

vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }))

import { authGuard, homeRouteForRole } from './guards'

describe('homeRouteForRole', () => {
  it.each([
    ['ADMIN', 'UserPermission'],
    ['OPERATOR', 'ClusterAudit'],
    ['AUDITOR', 'ReportList'],
    ['USER', 'NotificationCenter'],
    ['PHASE_ONE_TECHNICAL', 'AllFeedback'],
    ['PHASE_ONE_PRODUCT', 'AllFeedback'],
  ])('maps %s to an authorized landing page', (role, expected) => {
    expect(homeRouteForRole(role)).toEqual({ name: expected })
  })
})

describe('authGuard permission matrix', () => {
  beforeEach(() => {
    auth.user = { role: 'ADMIN' }
    auth.loadCurrentUser.mockReset()
    auth.logout.mockReset()
    auth.hasPermission.mockReset().mockImplementation((permission: string) => permission !== 'access:phase_one_workspace')
    document.title = ''
  })

  async function guard(meta: Record<string, unknown> = {}, fullPath = '/protected', name = 'Protected') {
    const next = vi.fn()
    await authGuard(
      { meta, fullPath, name } as unknown as RouteLocationNormalized,
      {} as RouteLocationNormalized,
      next,
    )
    return next
  }

  it('allows public pages without consulting authentication state', async () => {
    auth.user = null
    const next = await guard({ noAuth: true, title: '登录' }, '/login')
    expect(next).toHaveBeenCalledWith()
    expect(auth.loadCurrentUser).not.toHaveBeenCalled()
    expect(document.title).toBe('登录 - 学习机反馈与运营平台')
  })

  it('redirects anonymous users to login and preserves the destination', async () => {
    auth.user = null
    auth.loadCurrentUser.mockRejectedValueOnce(new Error('unauthorized'))
    const next = await guard({}, '/admin/users?tab=roles')
    expect(auth.logout).toHaveBeenCalledOnce()
    expect(next).toHaveBeenCalledWith({ name: 'Login', query: { redirect: '/admin/users?tab=roles' } })
  })

  it('loads the current user before evaluating role and permission', async () => {
    auth.user = null
    auth.loadCurrentUser.mockImplementation(async () => { auth.user = { role: 'OPERATOR' } })
    const next = await guard({ roles: ['ADMIN', 'OPERATOR'], permission: 'access:alert_center' })
    expect(auth.loadCurrentUser).toHaveBeenCalledOnce()
    expect(auth.hasPermission).toHaveBeenCalledWith('access:alert_center')
    expect(next).toHaveBeenCalledWith()
  })

  it('clears an invalid session when restoring the user fails', async () => {
    auth.user = null
    auth.loadCurrentUser.mockRejectedValue(new Error('expired'))
    const next = await guard({}, '/dashboard')
    expect(auth.logout).toHaveBeenCalledOnce()
    expect(next).toHaveBeenCalledWith({ name: 'Login', query: { redirect: '/dashboard' } })
  })

  it.each([
    ['OPERATOR', ['ADMIN'], 'ClusterAudit'],
    ['AUDITOR', ['ADMIN', 'OPERATOR'], 'ReportList'],
    ['USER', ['ADMIN', 'AUDITOR'], 'NotificationCenter'],
  ])('rejects %s when the allowed roles are %j', async (role, roles, home) => {
    auth.user = { role }
    const next = await guard({ roles })
    expect(next).toHaveBeenCalledWith({ name: home })
    expect(auth.hasPermission).not.toHaveBeenCalled()
  })

  it.each(['ADMIN', 'OPERATOR', 'AUDITOR', 'USER'])('rejects %s without the required permission', async (role) => {
    auth.user = { role }
    auth.hasPermission.mockReturnValue(false)
    const next = await guard({ roles: [role], permission: 'access:restricted' })
    expect(next).toHaveBeenCalledWith(homeRouteForRole(role))
  })

  it('allows access only after both role and permission checks pass', async () => {
    auth.user = { role: 'AUDITOR' }
    const next = await guard({ roles: ['ADMIN', 'AUDITOR'], permission: 'access:audit_logs' })
    expect(auth.hasPermission).toHaveBeenCalledWith('access:audit_logs')
    expect(next).toHaveBeenCalledWith()
  })

  it('requires the single workspace permission and a Feishu identity for feedback routes', async () => {
    auth.user = { role: 'AUDITOR', authProvider: 'SSO' }
    auth.hasPermission.mockReturnValue(false)
    const missing = await guard({ permission: 'access:phase_one_workspace', requiresFeishu: true })
    expect(missing).toHaveBeenCalledWith(homeRouteForRole('AUDITOR'))

    auth.hasPermission.mockReturnValue(true)
    auth.user = { role: 'AUDITOR', authProvider: 'LOCAL' }
    const local = await guard({ permission: 'access:phase_one_workspace', requiresFeishu: true })
    expect(local).toHaveBeenCalledWith(homeRouteForRole('AUDITOR'))

    auth.user = { role: 'AUDITOR', authProvider: 'SSO' }
    const allowed = await guard({ permission: 'access:phase_one_workspace', requiresFeishu: true }, '/channel/all', 'AllFeedback')
    expect(allowed).toHaveBeenCalledWith()
  })

  it('admits a local account to feedback routes only when the server reported the local relaxation', async () => {
    auth.hasPermission.mockReturnValue(true)
    auth.user = { role: 'AUDITOR', authProvider: 'LOCAL', workspaceAccessRelaxed: true }
    const relaxed = await guard({ permission: 'access:phase_one_workspace', requiresFeishu: true }, '/channel/all', 'AllFeedback')
    expect(relaxed).toHaveBeenCalledWith()
  })

  it('keeps feedback-only accounts inside the two authorized modules', async () => {
    auth.user = { role: 'PHASE_ONE_PRODUCT', roles: ['PHASE_ONE_PRODUCT'], authProvider: 'SSO' }
    auth.hasPermission.mockReturnValue(true)
    const denied = await guard({}, '/notifications', 'NotificationCenter')
    expect(denied).toHaveBeenCalledWith({ name: 'AllFeedback' })
    const allowed = await guard({ permission: 'access:phase_one_workspace', requiresFeishu: true }, '/channel/all', 'AllFeedback')
    expect(allowed).toHaveBeenCalledWith()
  })

  it('allows cluster detail for Feishu workspace users or legacy report readers only', async () => {
    auth.user = { role: 'PHASE_ONE_TECHNICAL', authProvider: 'SSO' }
    auth.hasPermission.mockImplementation((permission: string) => permission === 'access:phase_one_workspace')
    const phase = await guard({ workspaceOrPermission: 'access:ai_reports' }, '/report/cluster/1', 'ClusterDetail')
    expect(phase).toHaveBeenCalledWith()

    auth.user = { role: 'USER', authProvider: 'LOCAL' }
    auth.hasPermission.mockReturnValue(false)
    const denied = await guard({ workspaceOrPermission: 'access:ai_reports' }, '/report/cluster/1', 'ClusterDetail')
    expect(denied).toHaveBeenCalledWith(homeRouteForRole('USER'))
  })
})
