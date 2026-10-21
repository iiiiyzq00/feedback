package com.feedback.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Service

const val WEB_SESSION_COOKIE = "feedback_session"

/**
 * 在浏览器不可被 JavaScript 读取的位置保存会话凭据。
 *
 * 浏览器仅通过 HttpOnly、host-only Cookie 接收 Redis 会话的不透明标识；身份和权限事实留在服务端。
 */
@Service
class WebSessionCookieService(
    private val sessions: WebSessionStore,
    @param:Value("\${app.auth.session-cookie.secure:true}") private val secure: Boolean,
    @Value("\${app.auth.session-cookie.same-site:Strict}") sameSite: String
) {
    private val sameSite = sameSite.trim().replaceFirstChar(Char::uppercase).also {
        require(it in setOf("Strict", "Lax", "None")) { "AUTH_SESSION_COOKIE_SAME_SITE must be Strict, Lax, or None" }
        require(it != "None" || secure) { "SameSite=None session cookies must be Secure" }
    }

    /** 从请求 Cookie 中读取非空会话标识，不负责验证其格式或有效性。 */
    fun sessionId(request: HttpServletRequest): String? = request.cookies
        ?.firstOrNull { it.name == WEB_SESSION_COOKIE }
        ?.value
        ?.takeIf(String::isNotBlank)

    /** 登录成功后先撤销浏览器已有会话，再创建并写入新的固定期限会话 Cookie。 */
    fun establish(
        request: HttpServletRequest,
        response: HttpServletResponse,
        userId: Long,
        authVersion: Long,
    ) {
        sessionId(request)?.let(sessions::revoke)
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            cookie(sessions.create(userId, authVersion), sessions.ttlSeconds).toString(),
        )
    }

    /** 同时过期会话与 CSRF Cookie，并删除 Redis 中当前会话。 */
    fun clear(request: HttpServletRequest, response: HttpServletResponse) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString())
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            ResponseCookie.from("XSRF-TOKEN", "")
                .httpOnly(false)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(0)
                .build()
                .toString()
        )
        sessionId(request)?.let(sessions::revoke)
    }

    /** 统一应用 Cookie 安全属性，避免创建和清理路径出现属性不一致。 */
    private fun cookie(value: String, maxAgeSeconds: Long) = ResponseCookie.from(WEB_SESSION_COOKIE, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite(sameSite)
        .path("/")
        .maxAge(maxAgeSeconds)
        .build()
}
