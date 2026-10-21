package com.feedback.security

import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.model.auth.AuthorizationAuditClass
import com.feedback.model.auth.AuthorizationAuditOutcome
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.UUID

/**
 * Web API 请求审计过滤器。
 * 请求完成后异步入队脱敏审计；查询参数只记录哈希，审计失败不覆盖原 HTTP 响应。
 */
@Component
class RequestAuditFilter(private val audits: BusinessAuditService) : OncePerRequestFilter() {
    /** 排除非 API、SDK 网关和登录入口，避免重复记录由专用链路负责的安全事件。 */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/v1/") || request.requestURI.startsWith("/api/v1/sdk/") ||
            request.requestURI == "/api/v1/auth/login"

    /** 在下游完成后按最终状态入队审计；过滤器本身不创建数据库事务。 */
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val started = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            val principal = SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
            if (principal != null) {
                runCatching { enqueue(request, response, principal, started) }
            }
        }
    }

    /** 构造脱敏审计事件；参数值限制为每个键最多十个哈希。 */
    private fun enqueue(
        request: HttpServletRequest,
        response: HttpServletResponse,
        principal: WebPrincipal,
        started: Long,
    ) {
        val (operation, auditClass) = classify(request.method, request.requestURI)
        val requestId = request.getAttribute("requestId")?.toString()
            ?: request.getHeader("X-Request-Id")?.takeIf { it.matches(REQUEST_ID) }
            ?: UUID.randomUUID().toString()
        val params = request.parameterMap.mapValues { (_, values) -> values.map(::sha256).take(10) }
        val outcome = when {
            response.status == 401 || response.status == 403 -> AuthorizationAuditOutcome.DENIED
            response.status >= 400 -> AuthorizationAuditOutcome.FAILED
            else -> AuthorizationAuditOutcome.SUCCESS
        }
        audits.enqueue(
            BusinessAuditEvent(
                principal.userId,
                operation,
                auditClass.code,
                outcome.code,
                "WEB_API",
                request.requestURI.take(100),
                requestId,
                request.remoteAddr,
                mapOf(
                    "method" to request.method,
                    "path" to request.requestURI,
                    "status" to response.status,
                    "durationMs" to (System.nanoTime() - started) / 1_000_000,
                    "queryValueHashes" to params,
                ),
            ),
        )
    }

    /** 将稳定路由模式归类为审计动作和分类；未知路由保留 HTTP 方法兜底。 */
    private fun classify(method: String, path: String): Pair<String, AuthorizationAuditClass> {
        val write = method !in READ_METHODS
        return when {
            write && path.contains("/admin/users") && method == "POST" ->
                "CREATE_USER" to AuthorizationAuditClass.BUSINESS
            write && path.contains("/admin/users") &&
                (method == "DELETE" || path.endsWith("/disable")) ->
                "DISABLE_USER" to AuthorizationAuditClass.BUSINESS
            write && path.contains("/admin/users") -> "EDIT_USER" to AuthorizationAuditClass.BUSINESS
            write && path.contains("/admin/roles/") -> "ROLE_PERMISSION_EDIT" to AuthorizationAuditClass.SECURITY
            write && path.contains("/admin/configs") -> "CONFIG_UPDATE" to AuthorizationAuditClass.BUSINESS
            write && path.contains("/admin/alert-rules/oncall") -> "ONCALL_EDIT" to AuthorizationAuditClass.BUSINESS
            write && path.contains("/admin/alert-rules") -> "ROUTING_RULE_EDIT" to AuthorizationAuditClass.BUSINESS
            path.endsWith("/admin/masking-rules/test") -> "MASKING_TEST" to AuthorizationAuditClass.QUERY
            write && path.contains("/admin/masking-rules/ner-allowlist") ->
                "NER_WHITELIST_EDIT" to AuthorizationAuditClass.SECURITY
            write && path.contains("/admin/masking-rules") -> "MASKING_RULE_EDIT" to AuthorizationAuditClass.SECURITY
            write && path.contains("/masking-security/contacts/") -> "CONTACT_DECRYPT" to AuthorizationAuditClass.SECURITY
            path.contains("/reports/") && path.endsWith("/pdf") -> "EXPORT_REPORT" to AuthorizationAuditClass.SECURITY
            path == "/api/v1/reports/generate" -> "GENERATE_REPORT_REQUEST" to AuthorizationAuditClass.BUSINESS
            path.contains("/reports/") -> "VIEW_REPORT" to AuthorizationAuditClass.QUERY
            path.contains("/export") -> "EXPORT_DATA" to AuthorizationAuditClass.SECURITY
            path.contains("/rollback") -> "BATCH_ROLLBACK" to AuthorizationAuditClass.BUSINESS
            path.contains("/alerts/") && write -> "ACK_ALERT" to AuthorizationAuditClass.BUSINESS
            path.contains("/cluster") && write -> "MANUAL_CLUSTER_DECISION" to AuthorizationAuditClass.BUSINESS
            path == "/api/v1/import/jira/sync" && write -> "JIRA_SYNC_REQUEST" to AuthorizationAuditClass.BUSINESS
            path.contains("/ai-review") && write -> "MANUAL_CORRECTION" to AuthorizationAuditClass.BUSINESS
            (path.contains("/prompt") || path.contains("/skills")) && write ->
                "PROMPT_UPDATE" to AuthorizationAuditClass.BUSINESS
            path.matches(FEEDBACK_DETAIL_PATH) -> "VIEW_FEEDBACK_DETAIL" to AuthorizationAuditClass.QUERY
            path.contains("/feedback") -> "QUERY_FEEDBACK" to AuthorizationAuditClass.QUERY
            else -> "HTTP_$method" to if (write) AuthorizationAuditClass.BUSINESS else AuthorizationAuditClass.QUERY
        }
    }

    /** 对查询参数值生成 SHA-256，避免审计队列保存敏感明文。 */
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")
        val REQUEST_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
        val FEEDBACK_DETAIL_PATH = Regex(".*/feedback/[^/]+$")
    }
}
