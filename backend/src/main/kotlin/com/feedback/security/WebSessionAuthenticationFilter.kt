package com.feedback.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 每个请求解析不透明会话 Cookie，并把有效用户还原到 Spring Security 上下文。
 * Redis 会话中的 authVersion 必须与数据库一致，确保注销或权限变更可立即废止旧会话。
 */
@Component
class WebSessionAuthenticationFilter(
    private val sessions: WebSessionStore,
    private val users: WebUserDetailsService,
    private val sessionCookies: WebSessionCookieService
) : OncePerRequestFilter() {
    /** 无 Cookie、会话无效或用户失效时保持匿名，由后续授权链统一返回 401/403。 */
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val raw = sessionCookies.sessionId(request)
        if (raw != null && SecurityContextHolder.getContext().authentication == null) {
            val session = sessions.resolve(raw)
            if (session != null) {
                val principal = try {
                    users.loadUserById(session.userId)
                } catch (_: UsernameNotFoundException) {
                    null
                }
                if (principal != null && session.authVersion == principal.authVersion) {
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
                    request.setAttribute("WEB_SESSION_AUTHENTICATED", true)
                }
            }
        }
        chain.doFilter(request, response)
    }
}
