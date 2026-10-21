import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { recordPerformance } from '@/utils/performance'
import { truncateDateTimeToSeconds } from '@/utils/datetime'

const instance = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  withXSRFToken: true,
})

const getCache = new Map<string, { expiresAt: number; value: any }>()
const pendingGets = new Map<string, Promise<any>>()
const GET_CACHE_MS = 15_000

export type AuthAwareRequestConfig = AxiosRequestConfig & {
  __startedAt?: number
  /** 该请求离开页面时的浏览器会话代号。 */
  __authGeneration?: number
  /** Spring 下发缺失的 CSRF Cookie 后，做一次用户无感的透明重试。 */
  __csrfRetried?: boolean
  silent?: boolean
}

// 每次登录或退出都推进“会话代次”，用于识别属于旧会话的延迟响应。
let authenticationGeneration = 0
// 多个写请求同时发现 CSRF Cookie 缺失时，共享同一次引导请求。
let csrfBootstrapRequest: Promise<string | undefined> | undefined

/** 标记浏览器身份已经切换，使旧会话仍在途的响应失去导航权。 */
export function advanceAuthenticationGeneration() {
  authenticationGeneration += 1
}

/** 判断响应是否属于当前会话代次，防止旧 401 覆盖刚刚成功的新登录。 */
export function isCurrentAuthenticationGeneration(requestGeneration?: number) {
  return requestGeneration === authenticationGeneration
}

function cacheKey(url: string, config?: AxiosRequestConfig) {
  return `${url}?${JSON.stringify(config?.params ?? {})}`
}

export function clearRequestCache() {
  getCache.clear()
  pendingGets.clear()
}

const businessErrorMessages: Record<string, string> = {
  ALERT_RULE_NOT_PRODUCTION_READY: '生产准入未完成，请在“准入详情”中补齐数据契约、实现证据和独立验收',
  ALERT_RULE_NOT_PUBLISHABLE: '规则尚未满足发布条件，请先完成准入验收',
  ALERT_RULE_NOT_SHADOW: '只有影子验证版本可以执行正式发布',
}

function responseErrorMessage(error: any) {
  const body = error.response?.data
  const reasonCode = body?.details?.reasonCode
  if (reasonCode && businessErrorMessages[reasonCode]) return businessErrorMessages[reasonCode]
  const serverMessage = body?.message
  if (serverMessage && serverMessage !== 'Internal Server Error') return serverMessage
  return error.message || '网络异常'
}

function cookieValue(cookieHeader: string, name: string) {
  const prefix = `${encodeURIComponent(name)}=`
  const raw = cookieHeader.split(';').map(value => value.trim()).find(value => value.startsWith(prefix))
  if (!raw) return undefined
  try { return decodeURIComponent(raw.slice(prefix.length)) || undefined } catch { return undefined }
}

function existingXsrfHeader(config: AxiosRequestConfig) {
  const headers = config.headers as any
  return typeof headers?.get === 'function' ? headers.get('X-XSRF-TOKEN') : headers?.['X-XSRF-TOKEN']
}

function setXsrfHeader(config: AxiosRequestConfig, token: string) {
  const headers = config.headers as any
  if (typeof headers?.set === 'function') headers.set('X-XSRF-TOKEN', token)
  else config.headers = { ...(headers ?? {}), 'X-XSRF-TOKEN': token }
}

/** 获取浏览器可读的 CSRF token；缺失时调用公开端点初始化 Cookie。 */
export async function browserCsrfToken(cookieHeader = typeof document === 'undefined' ? '' : document.cookie) {
  const current = cookieValue(cookieHeader, 'XSRF-TOKEN')
  if (current) return current
  if (!csrfBootstrapRequest) {
    csrfBootstrapRequest = axios.get('/api/v1/auth/csrf', {
      headers: { Accept: 'application/json' },
      timeout: 10_000,
    }).then((response) => {
      const token = response.data?.data?.token
      return typeof token === 'string' && token.length > 0 ? token : undefined
    }).finally(() => { csrfBootstrapRequest = undefined })
  }
  return csrfBootstrapRequest
}

/** 登录和飞书握手尚未建立普通会话，其他写请求都必须携带 CSRF token。 */
function requiresCsrf(config: AxiosRequestConfig) {
  const method = config.method?.toUpperCase()
  if (!method || !['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) return false
  const url = String(config.url ?? '')
  return url !== '/auth/login' && !url.startsWith('/auth/feishu/')
}

/** 403 响应已带回新 CSRF Cookie 时，为原请求提供一次且仅一次的透明重试。 */
export function csrfRetryToken(
  status: number | undefined,
  config: AuthAwareRequestConfig | undefined,
  cookieHeader = typeof document === 'undefined' ? '' : document.cookie,
) {
  const method = config?.method?.toUpperCase()
  if (status !== 403 || !config || config.__csrfRetried || !method || !['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) return undefined
  if (existingXsrfHeader(config)) return undefined
  return cookieValue(cookieHeader, 'XSRF-TOKEN')
}

// 认证信息由浏览器通过 HttpOnly Cookie 自动携带；JavaScript 只能读取独立的 CSRF Cookie 并镜像到请求头。
instance.interceptors.request.use(async (config) => {
  const timedConfig = config as typeof config & AuthAwareRequestConfig
  timedConfig.__startedAt = performance.now()
  timedConfig.__authGeneration = authenticationGeneration
  if (requiresCsrf(config) && !existingXsrfHeader(config)) {
    const token = await browserCsrfToken()
    if (token) setXsrfHeader(config, token)
  }
  return config
})

// 响应拦截器 — 统一错误处理，并解包 ApiResponse 返回 data 字段
instance.interceptors.response.use(
  (response) => {
    recordPerformance({ type: 'api', name: `${response.config.method?.toUpperCase()} ${response.config.url}`, duration: performance.now() - ((response.config as any).__startedAt ?? performance.now()), status: 'success', detail: { status: response.status } })
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0 && body.code !== 200) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    // 后端统一返回 { code:0, data:..., message:... }，这里解包 data
    return truncateDateTimeToSeconds(body?.data ?? body)
  },
  (error) => {
    const config = error.config as AuthAwareRequestConfig | undefined
    recordPerformance({ type: 'api', name: `${config?.method?.toUpperCase() ?? 'UNKNOWN'} ${config?.url ?? 'unknown'}`, duration: performance.now() - (config?.__startedAt ?? performance.now()), status: 'error', detail: { status: error.response?.status, message: error.message } })
    const refreshedCsrf = csrfRetryToken(error.response?.status, config)
    if (refreshedCsrf && config) {
      config.__csrfRetried = true
      setXsrfHeader(config, refreshedCsrf)
      return instance.request(config)
    }
    if (error.response?.status === 401) {
      // 只有当前会话发出的请求才允许因 401 跳转；旧会话的延迟 401 不能冲掉刚完成的新登录。
      if (isCurrentAuthenticationGeneration(config?.__authGeneration)) {
        window.location.href = '/login'
      }
    } else if (config?.silent) {
      // 首页看板上的可选装饰数据，绝不能因为取不到就让整页表现为不可用。
    } else if (error.response?.status === 403) {
      ElMessage.error('权限不足')
    } else if (error.response?.status === 429) {
      ElMessage.error('请求过于频繁，请稍后重试')
    } else if (error.response?.status >= 500) {
      const serverMessage = error.response?.data?.message
      ElMessage.error(serverMessage && serverMessage !== 'Internal Server Error' ? serverMessage : '服务暂时不可用，请稍后重试')
    } else {
      ElMessage.error(responseErrorMessage(error))
    }
    return Promise.reject(error)
  },
)

/** 包装的 GET 请求，返回 Promise<any> */
export function get(url: string, config?: AxiosRequestConfig): Promise<any> {
  if (config?.signal) return instance.get(url, config) as any
  const key = cacheKey(url, config)
  const cached = getCache.get(key)
  if (cached && cached.expiresAt > Date.now()) return Promise.resolve(cached.value)
  const pending = pendingGets.get(key)
  if (pending) return pending
  const request = (instance.get(url, config) as Promise<any>)
    .then((value) => { getCache.set(key, { expiresAt: Date.now() + GET_CACHE_MS, value }); return value })
    .finally(() => pendingGets.delete(key))
  pendingGets.set(key, request)
  return request
}

/** 包装的 POST 请求，返回 Promise<any> */
export function post(url: string, data?: any, config?: AuthAwareRequestConfig): Promise<any> {
  clearRequestCache()
  return instance.post(url, data, config) as any
}

/** 包装的 PUT 请求，返回 Promise<any> */
export function put(url: string, data?: any, config?: AxiosRequestConfig): Promise<any> {
  clearRequestCache()
  return instance.put(url, data, config) as any
}

/** 包装的 DELETE 请求，返回 Promise<any> */
export function del(url: string, config?: AxiosRequestConfig): Promise<any> {
  clearRequestCache()
  return instance.delete(url, config) as any
}

export function download(url: string, config?: AxiosRequestConfig): Promise<Blob> {
  return instance.get(url, { ...config, responseType: 'blob' }) as any
}
