export type PerformanceMetric = {
  type: 'api' | 'route' | 'web-vital'
  name: string
  duration: number
  status?: 'success' | 'error'
  detail?: Record<string, unknown>
  timestamp: string
}

const STORAGE_KEY = 'feedback-platform-performance'
const MAX_METRICS = 100

export function recordPerformance(metric: Omit<PerformanceMetric, 'timestamp'>) {
  const entry: PerformanceMetric = { ...metric, duration: Math.round(metric.duration), timestamp: new Date().toISOString() }
  try {
    const previous = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '[]') as PerformanceMetric[]
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify([...previous, entry].slice(-MAX_METRICS)))
  } catch { /* 隐私模式或存储已满时不影响业务 */ }
  window.dispatchEvent(new CustomEvent('app-performance', { detail: entry }))
  if (import.meta.env.DEV) console.debug('[performance]', entry)
}

export function setupWebVitals() {
  if (!('PerformanceObserver' in window)) return
  const observe = (type: string, handler: (entries: PerformanceEntry[]) => void) => {
    try {
      const observer = new PerformanceObserver((list) => handler(list.getEntries()))
      observer.observe({ type, buffered: true })
    } catch { /* 浏览器不支持该指标 */ }
  }
  observe('paint', (entries) => entries.forEach((entry) => {
    if (entry.name === 'first-contentful-paint') recordPerformance({ type: 'web-vital', name: 'FCP', duration: entry.startTime })
  }))
  observe('largest-contentful-paint', (entries) => {
    const entry = entries[entries.length - 1]
    if (entry) recordPerformance({ type: 'web-vital', name: 'LCP', duration: entry.startTime })
  })
}
