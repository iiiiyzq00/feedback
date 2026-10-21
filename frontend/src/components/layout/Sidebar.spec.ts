import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/channel/station-feedback' }) }))
vi.mock('@/stores/app', () => ({ useAppStore: () => ({ sidebarCollapsed: false }) }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({
  user: { role: 'PHASE_ONE_PRODUCT', roles: ['PHASE_ONE_PRODUCT'], authProvider: 'SSO' }, hasPermission: () => true, isAdmin: false, isOperator: false,
}) }))

import Sidebar from './Sidebar.vue'
import sidebarSource from './Sidebar.vue?raw'

describe('Sidebar Station navigation', () => {
  it('exposes only the two feedback modules for an authorized Feishu user', () => {
    const wrapper = mount(Sidebar, { global: { stubs: {
      'el-menu': { template: '<nav><slot /></nav>' },
      'el-sub-menu': { template: '<section><slot name="title"/><slot /></section>' },
      'el-menu-item': { props: ['index', 'route'], template: '<a :data-index="index" :data-route="route?.name"><slot /></a>' },
      'el-icon': { template: '<i><slot /></i>' },
      Monitor: true, Collection: true, Connection: true, DataBoard: true, BellFilled: true,
      Setting: true, SetUp: true, Tickets: true, TrendCharts: true, LearningMachineIcon: true,
    } } })
    expect(wrapper.find('[data-index="/monitor/operations/station"]').exists()).toBe(false)
    expect(wrapper.get('[data-index="/channel/station-feedback"]').text()).toContain('学习机反馈')
    expect(wrapper.get('[data-index="/channel/station-feedback"]').attributes('data-route')).toBe('StationFeedback')
    expect(wrapper.find('[data-index="/admin/feishu-role-mappings"]').exists()).toBe(false)
    expect(wrapper.find('[data-index="/notifications"]').exists()).toBe(false)
    expect(wrapper.find('[data-index="/dashboard/overview"]').exists()).toBe(false)
    expect(wrapper.get('[data-index="/dashboard/analysis"]').text()).toContain('问题深度分析')
  })

  it('does not expose frontend entries whose backend closure was removed', () => {
    expect(sidebarSource).not.toContain('/dashboard/overview')
    expect(sidebarSource).not.toContain('/tasks/my')
    expect(sidebarSource).not.toContain('/report/rca')
    expect(sidebarSource).not.toContain('/admin/knowledge')
    expect(sidebarSource).toContain('/admin/system')
    expect(sidebarSource).not.toContain('/admin/audit-log')
  })
})
