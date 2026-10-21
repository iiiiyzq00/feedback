import { describe, expect, it, vi } from 'vitest'

vi.mock('@/stores/app', () => ({ useAppStore: () => ({ pageLoading: false }) }))
vi.mock('@/utils/performance', () => ({ recordPerformance: vi.fn() }))

import router from './index'

describe('feedback routes', () => {
  it('keeps the complete feedback pages and removes deleted JDBC feature entries', () => {
    const names = router.getRoutes().map((route) => route.name)
    expect(names).toContain('StationFeedback')
    expect(names).not.toContain('SdkFeedback')
    expect(names).not.toContain('StationOperations')
    expect(names).not.toContain('IngestionReceived')
    expect(names).not.toContain('DashboardOverview')
    expect(names).not.toContain('MyTasks')
    expect(names).not.toContain('RcaAudit')
    expect(names).not.toContain('KnowledgeBase')
    expect(names).toContain('SystemConfig')
    expect(names).not.toContain('AuditLog')
    expect(router.resolve('/channel/station-feedback').name).toBe('StationFeedback')
    expect(router.resolve('/channel/station-feedback').meta.requiresFeishu).toBe(true)
    expect(router.resolve('/channel/station-feedback').meta.permission).toBe('access:phase_one_workspace')
    expect(router.resolve('/dashboard/analysis').meta.requiresFeishu).toBe(true)
    expect(router.resolve('/dashboard/analysis').meta.permission).toBe('access:phase_one_workspace')
    expect(router.resolve('/report/cluster/1').meta.workspaceOrPermission).toBe('access:ai_reports')
    expect(router.resolve('/admin/feishu-role-mappings').name).toBe('FeishuRoleMapping')
    expect(router.resolve('/admin/feishu-role-mappings').meta.roles).toEqual(['ADMIN'])
    expect(router.resolve('/admin/cluster-shadow-preview').name).toBe('ClusterShadowPreview')
    expect(router.resolve('/admin/cluster-shadow-preview').meta.roles).toEqual(['ADMIN'])
  })
})
