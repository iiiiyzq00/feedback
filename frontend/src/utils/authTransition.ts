const AUTH_BOOT_FADE_MS = 180

/** 立即移除服务端静态 HTML 提供的飞书回调启动遮罩。 */
export function hideAuthBootImmediately(id = 'feishu-auth-boot') {
  const boot = document.getElementById(id)
  if (!boot) return
  boot.hidden = true
  boot.classList.remove('is-leaving')
  boot.removeAttribute('aria-hidden')
}

/**
 * 等目标路由获得两次浏览器绘制机会后，再淡出静态 OAuth 启动遮罩。
 * 这样回调提示、空 Vue 根节点和首个业务页面不会形成三次肉眼可见的闪屏。
 */
export function dismissAuthBootAfterPaint(id = 'feishu-auth-boot') {
  const boot = document.getElementById(id)
  if (!boot || boot.hidden) return

  window.requestAnimationFrame(() => {
    window.requestAnimationFrame(() => {
      boot.setAttribute('aria-hidden', 'true')
      boot.classList.add('is-leaving')
      window.setTimeout(() => hideAuthBootImmediately(id), AUTH_BOOT_FADE_MS)
    })
  })
}
