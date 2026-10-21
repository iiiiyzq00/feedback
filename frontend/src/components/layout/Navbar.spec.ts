import { describe, expect, it } from 'vitest'
import navbarSource from './Navbar.vue?raw'
import appLayoutSource from './AppLayout.vue?raw'

const compact = (source: string) => source.replace(/\s+/g, '')

describe('layout icon bindings', () => {
  it('binds on-demand Element Plus icons as components instead of unresolved strings', () => {
    expect(navbarSource).toContain(':icon="app.sidebarCollapsed ? Expand : Fold"')
    expect(navbarSource).toContain(':icon="Bell"')
    expect(navbarSource).toContain(':icon="UserFilled"')
    expect(navbarSource).not.toMatch(/\sicon="(?:Bell|UserFilled)"/)
    expect(appLayoutSource).toContain(':icon="UserFilled"')
    expect(appLayoutSource).not.toMatch(/\sicon="UserFilled"/)
  })

  it('removes notification polling and entry for feedback-only accounts', () => {
    expect(navbarSource).toContain('v-if="!feedbackOnly"')
    expect(navbarSource).toContain('isFeedbackWorkspaceOnlyUser(auth.user, auth.hasPermission)')
    expect(compact(navbarSource)).toContain(compact('if (!feedbackOnly.value)'))
  })

  it('removes global search from both the navbar and dialog for feedback-only accounts', () => {
    expect(navbarSource).toMatch(/<el-input\s+v-if="!feedbackOnly"/)
    expect(appLayoutSource).toMatch(/<el-dialog\s+v-if="!feedbackOnly"[\s\S]*?title="全局搜索"/)
  })

  it('confirms logout, clears the session and forces a fresh login-page navigation', () => {
    expect(navbarSource).toContain('await ElMessageBox.confirm(')
    expect(navbarSource).toContain("'确认退出登录'")
    expect(navbarSource).toContain('const serverLogout = auth.logout()')
    expect(navbarSource).toContain("window.location.replace('/?logged_out=1')")
    expect(navbarSource).not.toContain("window.location.replace('/login')")
    expect(navbarSource).toContain('new Promise<void>((resolve) => window.setTimeout(resolve, 300))')
    expect(navbarSource).not.toContain("await router.replace({ name: 'Login' })")
    expect(navbarSource).not.toContain("auth.logout().then(() => router.push('/login'))")
  })
})
