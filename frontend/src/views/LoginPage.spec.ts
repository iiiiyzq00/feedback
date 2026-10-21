import { describe, expect, it } from 'vitest'
import loginPageSource from './LoginPage.vue?raw'
import appShellSource from '../../index.html?raw'
import mainSource from '../main.ts?raw'

describe('Feishu login callback experience', () => {
  it('renders a dedicated completion state from the callback URL before mount', () => {
    expect(loginPageSource).toContain("const isFeishuCallback = computed(() => typeof route.query.feishu_code === 'string')")
    expect(loginPageSource).toContain('v-if="isFeishuCallback"')
    expect(loginPageSource).toContain('<div v-else class="login-card">')
  })

  it('does not show the password form or the verbose alert in the callback state', () => {
    const callbackCard = loginPageSource.slice(
      loginPageSource.indexOf('v-if="isFeishuCallback"'),
      loginPageSource.indexOf('<div v-else class="login-card">'),
    )
    expect(callbackCard).not.toContain('<el-form')
    expect(loginPageSource).not.toContain('已收到飞书授权结果，正在安全建立登录会话，请勿重复授权或关闭页面。')
  })

  it('shows the same completion state before Vue starts on a Feishu callback', () => {
    expect(mainSource).toContain('const initialQuery = new URLSearchParams(window.location.search)')
    expect(mainSource).toContain("initialQuery.has('feishu_code')")
    expect(mainSource).toContain("document.getElementById('feishu-auth-boot')")
    expect(appShellSource).toContain('id="feishu-auth-boot"')
    expect(appShellSource.indexOf('id="feishu-auth-boot"')).toBeLessThan(appShellSource.indexOf('id="app"'))
    expect(appShellSource).toContain('<div id="app"></div>')
    expect(appShellSource).toContain('.feishu-auth-boot.is-leaving')
    expect(appShellSource).toContain('<h1>正在登录</h1>')
    expect(loginPageSource).toContain('<h1>正在登录</h1>')
    expect(loginPageSource).not.toContain('<Loading />')
    expect(mainSource.indexOf('await router.isReady()')).toBeLessThan(mainSource.indexOf("app.mount('#app')"))
  })

  it('keeps executable boot logic out of inline scripts required to be blocked by CSP', () => {
    expect(appShellSource).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>[\s\S]*?<\/script>/i)
    expect(appShellSource).toContain('<script type="module" src="/src/main.ts"></script>')
  })

  it('navigates directly to the role home instead of bouncing through the root redirect', () => {
    expect(loginPageSource).toContain("!returnPath || returnPath === '/'")
    expect(loginPageSource).toContain('await router.replace(target)')
  })

  it('keeps the static boot frame through navigation and dismisses it after paint', () => {
    expect(loginPageSource).toContain('dismissAuthBootAfterPaint()')
    expect(loginPageSource.indexOf('await router.replace(target)')).toBeLessThan(
      loginPageSource.indexOf('dismissAuthBootAfterPaint()'),
    )
    expect(loginPageSource).toContain('hideAuthBootImmediately()')
  })

  it('provides a non-blank boot state while a root reload returns to login', () => {
    expect(mainSource).toContain("initialQuery.has('logged_out')")
    expect(mainSource).toContain("document.getElementById('logout-boot')")
    expect(appShellSource).toContain('id="logout-boot"')
    expect(appShellSource).toContain('<h1>正在退出</h1>')
    expect(mainSource.indexOf('const initialQuery')).toBeLessThan(mainSource.indexOf('await router.isReady()'))
    expect(mainSource).toContain("initialQuery.has('logged_out')")
  })

  it('shows a retryable message when the Feishu provider times out', () => {
    expect(loginPageSource).toContain("typeof route.query.feishu_error === 'string'")
    expect(loginPageSource).toContain("route.query.feishu_error === 'provider_timeout'")
    expect(loginPageSource).toContain('连接飞书服务超时')
  })
})
