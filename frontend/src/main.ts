import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import './styles/global.css'
import { setupWebVitals } from './utils/performance'
import { VueQueryPlugin } from '@tanstack/vue-query'
import { queryClient } from './query/client'
import { dismissAuthBootAfterPaint } from './utils/authTransition'

// 必须在 Vue 和路由初始化前显示 OAuth/注销启动遮罩，避免刷新阶段闪现空白页。
// 逻辑保留在外部模块中，因为应用 CSP 会拒绝 index.html 内联脚本。
const initialQuery = new URLSearchParams(window.location.search)
if (initialQuery.has('feishu_code')) {
  const boot = document.getElementById('feishu-auth-boot')
  if (boot) boot.hidden = false
} else if (initialQuery.has('logged_out')) {
  const boot = document.getElementById('logout-boot')
  if (boot) boot.hidden = false
}

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(VueQueryPlugin, { queryClient })

// 开发态先预热回调后常用的懒加载页面，避免 Vite 在短码已兑换后因依赖重优化刷新页面。
// 生产构建仍保留正常的懒加载和分包边界。
if (import.meta.env.DEV) {
  await Promise.allSettled([
    import('./components/layout/AppLayout.vue'),
    import('./views/channel/AllFeedbackPage.vue'),
    import('./views/channel/StationFeedbackPage.vue'),
    import('./views/channel/JiraFeedbackPage.vue'),
    import('./views/channel/QualityFeedbackPage.vue'),
    import('./views/channel/RedblackFeedbackPage.vue'),
    import('./views/dashboard/ProblemAnalysis.vue'),
  ])
}

// 等首个路由及懒加载组件就绪后再挂载，确保 index.html 的固定回调遮罩不会被空 #app 提前替换。
await router.isReady()
app.mount('#app')
if (initialQuery.has('logged_out')) {
  dismissAuthBootAfterPaint('logout-boot')
}
setupWebVitals()
