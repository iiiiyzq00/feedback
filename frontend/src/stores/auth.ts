import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi, logout as logoutApi, getUserInfo, exchangeFeishuLogin } from '@/api/auth'
import { advanceAuthenticationGeneration, clearRequestCache } from '@/api/request'
import { queryClient } from '@/query/client'

/** 前端登录态所需的最小用户快照；权限判定以服务端返回的 permissions 为准。 */
export interface UserInfo {
  id: number
  username: string
  realName: string
  role: 'ADMIN' | 'OPERATOR' | 'AUDITOR' | 'USER' | 'PHASE_ONE_TECHNICAL' | 'PHASE_ONE_PRODUCT'
  roles?: string[]
  permissions: string[]
  authProvider?: 'SSO' | 'LOCAL'
  /**
   * 服务端下发的本地开发放宽标记（app.security.local-workspace.enabled）。
   * 为 true 时 LOCAL（账号密码）主体同样可以进入反馈工作区页面；生产部署恒为 false。
   */
  workspaceAccessRelaxed?: boolean
}

/**
 * 全局鉴权状态仓库。
 * 负责建立、恢复和清除浏览器登录态，不在前端持久化任何可用于认证的凭据。
 */
export const useAuthStore = defineStore('auth', () => {
  // 清除旧版本遗留在 Web Storage 中的 bearer token；当前认证只使用 HttpOnly Cookie。
  try { localStorage.removeItem('token') } catch { /* 存储可能不可用 */ }
  const user = ref<UserInfo | null>(null)

  // 常用身份判断统一从 user 派生，避免业务页面各自解释“是否登录”。
  const isLoggedIn = computed(() => user.value !== null)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const isOperator = computed(() => user.value?.role === 'OPERATOR')

  /** 判断当前用户是否拥有服务端下发的原子权限。 */
  function hasPermission(perm: string): boolean {
    return user.value?.permissions?.includes(perm) ?? false
  }

  /** 提交本地账号密码，成功后推进会话代次并保存用户快照。 */
  async function login(username: string, password: string) {
      const res = await loginApi({ username, password })
      advanceAuthenticationGeneration()
      user.value = res.user
  }

  /** 兑换飞书一次性登录码，并返回 OAuth state 中保存的登录后地址。 */
  async function loginWithFeishuCode(code: string) {
    const res = await exchangeFeishuLogin(code)
    advanceAuthenticationGeneration()
    user.value = res.user
    return res.returnPath as string
  }

  /**
   * 退出登录时先清空所有浏览器可见状态，再尽力通知服务端撤销会话。
   * 即使注销接口超时或会话已过期，受保护数据也不会继续留在当前页面。
   */
  async function logout() {
    advanceAuthenticationGeneration()
    user.value = null
    clearRequestCache()
    queryClient.clear()
    try {
      await logoutApi()
    } catch {
      // 浏览器侧注销具有最终性，服务端会话缺失不能阻止页面回到登录态。
    }
  }

  /** 应用启动或进入受保护路由时，通过会话 Cookie 恢复当前用户。 */
  async function loadCurrentUser() {
    user.value = await getUserInfo()
    return user.value
  }

  /** 用可信接口已返回的用户快照直接同步 store。 */
  function setUser(u: UserInfo) {
    user.value = u
  }

  return { user, isLoggedIn, isAdmin, isOperator, hasPermission, login, loginWithFeishuCode, logout, loadCurrentUser, setUser }
})
