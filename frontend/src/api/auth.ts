import { get, post } from './request'

// 仅在网关明确返回“服务暂不可用”时短暂重试授权初始化，避免重复创建 OAuth state。
const FEISHU_AUTHORIZE_RETRY_DELAYS_MS = [350, 700]

/** 判断失败是否来自可安全重试的网关瞬时状态，而不是业务拒绝或未知网络错误。 */
function isTransientGatewayFailure(error: any) {
  const status = error?.response?.status
  return status === 502 || status === 503 || status === 504
}

/** 浏览器端非阻塞退避，用于拉开相邻的飞书授权初始化请求。 */
function delay(ms: number) {
  return new Promise<void>(resolve => window.setTimeout(resolve, ms))
}

/** 使用本地用户名和密码创建服务端 Web 会话。 */
export function login(data: { username: string; password: string }) {
  return post('/auth/login', data)
}

/**
 * 提交本地自助注册。
 * 入口是否开启由服务端决定；关闭时接口按资源不存在返回（404），前端只提示不可用。
 */
export function register(data: { username: string; password: string; displayName?: string }) {
  return post('/auth/register', data)
}

/**
 * 申请飞书 OAuth 授权地址。
 * returnPath 由后端绑定进一次性 state；这里只对 502/503/504 做有限重试。
 */
export async function beginFeishuLogin(returnPath: string) {
  const url = `/auth/feishu/authorize?returnPath=${encodeURIComponent(returnPath)}`
  let lastError: unknown

  for (let attempt = 0; attempt <= FEISHU_AUTHORIZE_RETRY_DELAYS_MS.length; attempt += 1) {
    try {
      // 滚动发布时前端可能早于后端就绪：隐藏中间错误提示，只重试明确的网关失败。
      // 4xx 或语义不明的网络错误直接结束，防止后端已经落库的 OAuth state 被重复创建。
      return await post(url, undefined, { silent: true })
    } catch (error) {
      lastError = error
      if (!isTransientGatewayFailure(error) || attempt === FEISHU_AUTHORIZE_RETRY_DELAYS_MS.length) throw error
      await delay(FEISHU_AUTHORIZE_RETRY_DELAYS_MS[attempt])
    }
  }

  throw lastError
}

/** 用后端签发的一次性登录码换取 HttpOnly 会话，并返回用户信息与原始目标地址。 */
export function exchangeFeishuLogin(code: string) {
  return post('/auth/feishu/exchange', { code })
}

/** 撤销当前服务端会话；浏览器本地状态由 auth store 负责同步清理。 */
export function logout() {
  return post('/auth/logout')
}

/** 根据浏览器携带的会话 Cookie 恢复当前登录用户。 */
export function getUserInfo() {
  return get('/auth/me')
}
