import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import ElementPlus from 'unplugin-element-plus/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { resolve } from 'path'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver()],
    }),
    ElementPlus(),
  ],
  resolve: {
    alias: {
      '@': resolve(import.meta.dirname, 'src'),
    },
  },
  optimizeDeps: {
    // OAuth 可能直接回落到某个懒加载的渠道路由。冷启动时扫描
    // 所有路由，避免它生成的 Element Plus 导入把
    // 优化器打失效、在一次性登录码被消费的过程中刷新页面。
    entries: ['index.html', 'src/**/*.{vue,ts}'],
    ignoreOutdatedRequests: true,
    include: [
      'element-plus/es',
      'element-plus/es/components/*/style/css',
      '@element-plus/icons-vue',
    ],
  },
  server: {
    port: 5173,
    headers: {
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' ws: wss:; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'self'",
    },
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 4173,
    host: '127.0.0.1',
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    server: {
      deps: {
        inline: ['element-plus'],
      },
    },
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,vue}'],
      exclude: [
        'src/**/*.d.ts',
        'src/**/*.{test,spec}.ts',
      ],
      reporter: ['text', 'json-summary', 'lcov', 'html'],
      reportsDirectory: 'coverage',
      thresholds: {
        // 2026-08-11 批量操作测试后的实测基线：6.36/7.20/5.58/7.00。
        // 这些是回归下限；新增关键路径测试时应当调高。
        statements: 6.1,
        branches: 6.9,
        functions: 5.3,
        lines: 6.7,
      },
    },
  },
  build: {
    manifest: true,
    // ECharts 保持为独立的懒加载第三方包。Element Plus 的组件
    // 与样式按路由导入；整库导入会超出这里设的守卫阈值。
    chunkSizeWarningLimit: 700,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('/echarts/') || id.includes('/zrender/')) return 'charts'
          // 让 Axios 及其少量传递依赖留在 Rollup 的
          // 自然分块图里。只把 axios 强塞进手动分块，可能
          // 与入口分块形成运行时循环依赖，导致页面白屏。
          if (id.includes('/vue/') || id.includes('/vue-router/') || id.includes('/pinia/')) return 'vue-vendor'
          return undefined
        },
      },
    },
  },
})
