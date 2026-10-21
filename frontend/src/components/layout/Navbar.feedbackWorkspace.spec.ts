import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'

const testState = vi.hoisted(() => ({
  user: {
    id: 1,
    username: 'feedback-user',
    realName: '反馈用户',
    role: 'PHASE_ONE_PRODUCT',
    permissions: ['access:phase_one_workspace'],
    authProvider: 'SSO',
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ matched: [], path: '/channel/all' }),
}))
vi.mock('@/stores/app', () => ({
  useAppStore: () => ({
    sidebarCollapsed: false,
    toggleSidebar: vi.fn(),
    openGlobalSearch: vi.fn(),
  }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: testState.user,
    hasPermission: (permission: string) => testState.user.permissions.includes(permission),
    logout: vi.fn(),
  }),
}))
vi.mock('@/api/notification', () => ({ getInAppUnreadCount: vi.fn().mockResolvedValue({ count: 0 }) }))

import Navbar from './Navbar.vue'

const mountedWrappers: VueWrapper[] = []

function mountNavbar() {
  const wrapper = mount(Navbar, {
    global: {
      stubs: {
        'el-input': { template: '<input data-testid="global-search" />' },
        'el-button': { template: '<button><slot /></button>' },
        'el-badge': { template: '<div><slot /></div>' },
        'el-breadcrumb': { template: '<nav><slot /></nav>' },
        'el-breadcrumb-item': { template: '<span><slot /></span>' },
        'el-dropdown': { template: '<div><slot /><slot name="dropdown" /></div>' },
        'el-dropdown-menu': { template: '<div><slot /></div>' },
        'el-dropdown-item': { template: '<div><slot /></div>' },
        'el-avatar': true,
        'el-icon': { template: '<i><slot /></i>' },
      },
    },
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
})

describe('Navbar feedback shell visibility', () => {
  it.each(['PHASE_ONE_PRODUCT', 'PHASE_ONE_TECHNICAL'])(
    'does not render global search for %s',
    (role) => {
      testState.user.role = role
      const wrapper = mountNavbar()
      expect(wrapper.find('[data-testid="global-search"]').exists()).toBe(false)
    },
  )

  it('keeps global search for administrators with workspace permission', () => {
    testState.user.role = 'ADMIN'
    const wrapper = mountNavbar()
    expect(wrapper.find('[data-testid="global-search"]').exists()).toBe(true)
  })
})
