export type ExternalWindowResult = 'OPENED' | 'BLOCKED'

/**
 * 在浏览器点击手势仍然有效时先开标签页，再异步解析
 * 授权后的 URL。这样既绕过弹窗拦截，也不暴露 opener。
 */
export async function openAuthorizedExternalWindow(loadUrl: () => Promise<string>): Promise<ExternalWindowResult> {
  const externalWindow = window.open('about:blank', '_blank')
  if (!externalWindow) return 'BLOCKED'
  externalWindow.opener = null
  try {
    const value = await loadUrl()
    const url = new URL(value, window.location.origin)
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('EXTERNAL_URL_PROTOCOL_INVALID')
    externalWindow.location.replace(url.toString())
    return 'OPENED'
  } catch (error) {
    externalWindow.close()
    throw error
  }
}
