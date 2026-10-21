package com.feedback.security

import com.feedback.security.station.StationGatewayAuthenticationFilter

import com.feedback.config.WebSecurityConfig
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.stream.Stream
import io.micrometer.core.instrument.MeterRegistry
import com.feedback.manager.sdk.SdkRequestTelemetryRecorder

/** 走真实的 Spring SecurityFilterChain，但不调用任何业务服务。 */
@WebMvcTest(controllers = [AuthorizationMatrixProbeController::class])
@Import(WebSecurityConfig::class, SecurityErrorWriter::class,AccessAuthorization::class,io.micrometer.core.instrument.simple.SimpleMeterRegistry::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpRoleAuthorizationMatrixTest {
    @Autowired lateinit var mvc: MockMvc

    @MockBean lateinit var webSessionFilter: WebSessionAuthenticationFilter
    @MockBean lateinit var auditFilter: RequestAuditFilter
    @MockBean lateinit var stationGatewayFilter: StationGatewayAuthenticationFilter
    @MockBean lateinit var sdkRequestTelemetryRecorder: SdkRequestTelemetryRecorder

    @BeforeEach
    fun passThroughApplicationFilters() {
        org.mockito.Mockito.doAnswer { invocation ->
            val chain = invocation.arguments[2] as FilterChain
            chain.doFilter(invocation.arguments[0] as jakarta.servlet.ServletRequest, invocation.arguments[1] as jakarta.servlet.ServletResponse)
            null
        }.`when`(webSessionFilter).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())
        org.mockito.Mockito.doAnswer { invocation ->
            val chain = invocation.arguments[2] as FilterChain
            chain.doFilter(invocation.arguments[0] as jakarta.servlet.ServletRequest, invocation.arguments[1] as jakarta.servlet.ServletResponse)
            null
        }.`when`(auditFilter).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())
        org.mockito.Mockito.doAnswer { invocation ->
            val chain = invocation.arguments[2] as FilterChain
            chain.doFilter(invocation.arguments[0] as jakarta.servlet.ServletRequest, invocation.arguments[1] as jakarta.servlet.ServletResponse)
            null
        }.`when`(stationGatewayFilter).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    fun `enforces role and permission matrix`(case: MatrixCase) {
        val call = request(case.method, case.path).with(user(principal(case.role)))
        mvc.perform(call).andExpect(if (case.allowed) status().isOk else status().isForbidden)
    }

    @Test
    fun `protected API rejects missing authentication`() {
        mvc.perform(request(HttpMethod.GET, "/api/v1/feedback"))
            .andExpect(status().isUnauthorized)
        mvc.perform(request(HttpMethod.GET, "/api/v1/feedback/1/attachments"))
            .andExpect(status().isUnauthorized)
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/configs"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `API responses carry explicit browser security headers`() {
        mvc.perform(request(HttpMethod.GET, "/api/v1/feedback").with(user(principal(Role.AUDITOR))))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
    }

    @Test
    fun `browser cookie sessions require CSRF on state changing requests`() {
        val withoutCsrf = request(HttpMethod.POST, "/api/v1/feedback/export")
            .with(user(principal(Role.AUDITOR)))
            .cookie(Cookie(WEB_SESSION_COOKIE, "signed-session"))
        mvc.perform(withoutCsrf).andExpect(status().isForbidden)

        val csrfToken = "96f0ecdf-1285-4d56-87a2-85184f928442"
        mvc.perform(
            request(HttpMethod.POST, "/api/v1/feedback/export")
                .with(user(principal(Role.AUDITOR)))
                .header("X-XSRF-TOKEN", csrfToken)
                .cookie(
                    Cookie(WEB_SESSION_COOKIE, "signed-session"),
                    Cookie("XSRF-TOKEN", csrfToken)
                )
        ).andExpect(status().isOk)
    }

    @Test
    fun `empty expired browser cookie does not turn unauthenticated POST into CSRF forbidden`() {
        mvc.perform(
            request(HttpMethod.POST, "/api/v1/feedback/export")
                .cookie(Cookie(WEB_SESSION_COOKIE, ""))
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `phase one product account is confined to workspace reads exports and direct evidence`() {
        val principal = WebPrincipal(99, "phase", "", "Phase One", setOf("PHASE_ONE_PRODUCT"), setOf("access:phase_one_workspace"), authProvider = "SSO")
        val allowed = listOf(
            HttpMethod.GET to "/api/v1/feedback",
            HttpMethod.GET to "/api/v1/feedback/1",
            HttpMethod.GET to "/api/v1/feedback/1/attachments",
            HttpMethod.GET to "/api/v1/feedback/export",
            HttpMethod.POST to "/api/v1/feedback/export",
            HttpMethod.GET to "/api/v1/feedback/1/diagnostics",
            HttpMethod.POST to "/api/v1/feedback/1/diagnostics/authorize",
            HttpMethod.POST to "/api/v1/feedback/1/attachments/2/authorize",
            HttpMethod.POST to "/api/v1/feedback/1/attachments/2/authorize-preview",
            HttpMethod.POST to "/api/v1/feedback/1/attachments/authorize-previews",
            HttpMethod.GET to "/api/v1/feedback/1/attachments/2/preview",
            HttpMethod.GET to "/api/v1/dashboard/problem-analysis",
            HttpMethod.GET to "/api/v1/reports/cluster/1",
            HttpMethod.GET to "/api/v1/reports/cluster/1/feedback"
        )
        allowed.forEach { (method, path) -> mvc.perform(request(method, path).with(user(principal))).andExpect(status().isOk) }

        val denied = listOf(
            HttpMethod.GET to "/api/v1/feedback/my-tasks",
            HttpMethod.POST to "/api/v1/feedback/1/reminders",
            HttpMethod.GET to "/api/v1/in-app-notifications/unread-count",
            HttpMethod.GET to "/api/v1/product-feedback",
            HttpMethod.GET to "/api/v1/channel-governance/overview",
            HttpMethod.GET to "/api/v1/reports/feedback/1/rca-knowledge",
            HttpMethod.GET to "/api/v1/reports/cluster/1/rca",
            HttpMethod.GET to "/api/v1/cluster/review"
        )
        denied.forEach { (method, path) -> mvc.perform(request(method, path).with(user(principal))).andExpect(status().isForbidden) }

        val localWorkspace = WebPrincipal(100, "local-phase", "", "Local", setOf("USER"), setOf("access:phase_one_workspace"), authProvider = "LOCAL")
        mvc.perform(request(HttpMethod.GET, "/api/v1/reports/cluster/1").with(user(localWorkspace))).andExpect(status().isForbidden)
    }

    @Test
    fun `original log routes require only SSO workspace qualification at the filter chain`() {
        val rawTechnical = workspacePrincipal(
            roles = setOf("PHASE_ONE_TECHNICAL"),
            permissions = setOf("access:phase_one_workspace", "access:phase_one_raw_content")
        )
        val technicalWithoutRaw = workspacePrincipal(
            roles = setOf("PHASE_ONE_TECHNICAL"),
            permissions = setOf("access:phase_one_workspace")
        )
        val product = workspacePrincipal(
            roles = setOf("PHASE_ONE_PRODUCT"),
            permissions = setOf("access:phase_one_workspace")
        )
        val admin = workspacePrincipal(
            roles = setOf("ADMIN"),
            permissions = setOf("access:phase_one_workspace")
        )
        val missingWorkspace = workspacePrincipal(
            roles = setOf("PHASE_ONE_TECHNICAL"),
            permissions = setOf("access:phase_one_raw_content")
        )
        val localWithPermissions = workspacePrincipal(
            roles = setOf("PHASE_ONE_TECHNICAL"),
            permissions = setOf("access:phase_one_workspace", "access:phase_one_raw_content"),
            authProvider = "LOCAL"
        )
        val routes = listOf(
            HttpMethod.POST to "/api/v1/feedback/1/attachments/2/authorize-original-download",
            HttpMethod.GET to "/api/v1/feedback/1/attachments/2/original"
        )

        routes.forEach { (method, path) ->
            listOf(rawTechnical, technicalWithoutRaw, product, admin).forEach { principal ->
                mvc.perform(request(method, path).with(user(principal))).andExpect(status().isOk)
            }
            listOf(missingWorkspace, localWithPermissions).forEach { principal ->
                mvc.perform(request(method, path).with(user(principal))).andExpect(status().isForbidden)
            }
        }
    }

    private fun matrix(): Stream<Arguments> {
        val roles = Role.entries
        val endpoints = listOf(
            Endpoint("feedback read", HttpMethod.GET, "/api/v1/feedback", setOf(Role.ADMIN, Role.OPERATOR, Role.AUDITOR)),
            Endpoint("feedback attachments", HttpMethod.GET, "/api/v1/feedback/1/attachments", setOf(Role.ADMIN, Role.OPERATOR, Role.AUDITOR)),
            Endpoint("feedback export", HttpMethod.GET, "/api/v1/feedback/export", setOf(Role.ADMIN, Role.OPERATOR, Role.AUDITOR)),
            Endpoint("feedback export post", HttpMethod.POST, "/api/v1/feedback/export", setOf(Role.ADMIN, Role.OPERATOR, Role.AUDITOR)),
            Endpoint("feedback write", HttpMethod.PUT, "/api/v1/feedback/batch-status", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("search", HttpMethod.GET, "/api/v1/search/suggest", setOf(Role.ADMIN, Role.OPERATOR, Role.AUDITOR)),
            Endpoint("similar search", HttpMethod.GET, "/api/v1/search/similar", setOf(Role.ADMIN, Role.OPERATOR, Role.AUDITOR)),
            Endpoint("dashboard", HttpMethod.GET, "/api/v1/dashboard/overview", roles.toSet()),
            Endpoint("dashboard ask", HttpMethod.POST, "/api/v1/dashboard/ask", setOf(Role.ADMIN, Role.OPERATOR, Role.AUDITOR)),
            Endpoint("alerts read", HttpMethod.GET, "/api/v1/alerts", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("alerts write", HttpMethod.POST, "/api/v1/alerts/1/ack", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("alerts transfer", HttpMethod.POST, "/api/v1/alerts/1/transfer", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("alerts silence", HttpMethod.POST, "/api/v1/alerts/1/silence", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("alerts resolve", HttpMethod.POST, "/api/v1/alerts/1/resolve", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("imports", HttpMethod.GET, "/api/v1/import/batches", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("import upload", HttpMethod.POST, "/api/v1/import/upload", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("import chunk upload", HttpMethod.POST, "/api/v1/import/upload/chunk", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("import chunk complete", HttpMethod.POST, "/api/v1/import/upload/chunk/00000000-0000-0000-0000-000000000000/complete", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("import confirm", HttpMethod.POST, "/api/v1/import/batches/B-1/confirm", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("import rollback", HttpMethod.POST, "/api/v1/import/batches/B-1/rollback", setOf(Role.ADMIN)),
            Endpoint("cluster review", HttpMethod.GET, "/api/v1/cluster/review", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("cluster batch review", HttpMethod.POST, "/api/v1/cluster/review/batch", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("AI review read", HttpMethod.GET, "/api/v1/ai-reviews", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("AI review write", HttpMethod.POST, "/api/v1/ai-reviews/1", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("AI batch review", HttpMethod.POST, "/api/v1/ai-reviews/batch", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("reports read", HttpMethod.GET, "/api/v1/reports", setOf(Role.ADMIN, Role.OPERATOR, Role.AUDITOR)),
            Endpoint("reports generate", HttpMethod.POST, "/api/v1/reports/generate", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("admin users", HttpMethod.GET, "/api/v1/admin/users", setOf(Role.ADMIN)),
            Endpoint("admin configs", HttpMethod.GET, "/api/v1/admin/configs", setOf(Role.ADMIN)),
            Endpoint("admin knowledge", HttpMethod.GET, "/api/v1/admin/knowledge", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("admin alert rules", HttpMethod.GET, "/api/v1/admin/alert-rules", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("audit logs", HttpMethod.GET, "/api/v1/admin/audit-logs", setOf(Role.ADMIN, Role.AUDITOR)),
            Endpoint("masking rules", HttpMethod.GET, "/api/v1/admin/masking-rules", setOf(Role.ADMIN, Role.AUDITOR)),
            Endpoint("masking quarantine", HttpMethod.GET, "/api/v1/masking-security/quarantine", setOf(Role.ADMIN)),
            Endpoint("masking exception close", HttpMethod.POST, "/api/v1/masking-security/close", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("station operations read", HttpMethod.GET, "/api/v1/ingestion-operations/station", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("station operations retry", HttpMethod.POST, "/api/v1/ingestion-operations/station/retry", setOf(Role.ADMIN, Role.OPERATOR)),
            Endpoint("alert loop health", HttpMethod.GET, "/api/v1/admin/alert-rules/loop-health", setOf(Role.ADMIN, Role.AUDITOR)),
            Endpoint("actuator metrics", HttpMethod.GET, "/actuator/metrics", setOf(Role.ADMIN)),
        )
        return endpoints.flatMap { endpoint -> roles.map { role ->
            val case = MatrixCase(role, endpoint.method, endpoint.path, role in endpoint.allowed)
            Arguments.of(Named.of("${role.name} ${endpoint.name} -> ${if (case.allowed) "ALLOW" else "DENY"}", case))
        } }.stream()
    }

    private fun principal(role: Role): WebPrincipal {
        val permissions = when (role) {
            Role.ADMIN -> setOf("feedback:read", "dashboard:read", "admin:manage", "access:user_management", "access:system_config", "access:routing_config", "access:audit_logs", "access:masking_view", "access:masking_edit", "access:masking_quarantine", "access:alert_loop_health", "access:alert_center", "action:ack_alert", "access:import_management", "action:upload_file", "access:dashboard_analysis", "access:ai_reports", "action:generate_reports", "action:export_data", "action:edit_status", "access:phase_one_workspace")
            Role.OPERATOR -> setOf("feedback:read", "dashboard:read", "cluster:review", "access:routing_config", "access:alert_center", "action:ack_alert", "access:import_management", "action:upload_file", "access:dashboard_analysis", "access:ai_reports", "action:generate_reports", "action:export_data", "action:edit_status", "access:phase_one_workspace")
            Role.AUDITOR -> setOf("feedback:read", "dashboard:read", "access:audit_logs", "access:masking_view", "access:alert_loop_health", "access:dashboard_analysis", "access:ai_reports", "access:phase_one_workspace")
            Role.USER -> setOf("feedback:read", "dashboard:read")
        }
        return WebPrincipal(role.ordinal.toLong() + 1, role.name.lowercase(), "unused", role.name, setOf(role.name), permissions, authProvider = "SSO")
    }

    private fun workspacePrincipal(
        roles: Set<String>,
        permissions: Set<String>,
        authProvider: String = "SSO"
    ) = WebPrincipal(99, "workspace", "unused", "Workspace", roles, permissions, authProvider = authProvider)

    enum class Role { ADMIN, OPERATOR, AUDITOR, USER }
    data class Endpoint(val name: String, val method: HttpMethod, val path: String, val allowed: Set<Role>)
    data class MatrixCase(val role: Role, val method: HttpMethod, val path: String, val allowed: Boolean)
}

@RestController
class AuthorizationMatrixProbeController {
    @RequestMapping(
        "/api/v1/feedback", "/api/v1/feedback/export", "/api/v1/feedback/batch-status",
        "/api/v1/feedback/1", "/api/v1/feedback/1/attachments", "/api/v1/feedback/1/diagnostics", "/api/v1/feedback/1/diagnostics/authorize", "/api/v1/feedback/1/attachments/2/authorize", "/api/v1/feedback/1/attachments/2/authorize-preview", "/api/v1/feedback/1/attachments/authorize-previews", "/api/v1/feedback/1/attachments/2/preview",
        "/api/v1/feedback/1/attachments/2/authorize-original-download", "/api/v1/feedback/1/attachments/2/original",
        "/api/v1/feedback/my-tasks", "/api/v1/feedback/1/reminders",
        "/api/v1/search/suggest", "/api/v1/search/similar", "/api/v1/dashboard/overview", "/api/v1/dashboard/ask",
        "/api/v1/dashboard/problem-analysis", "/api/v1/in-app-notifications/unread-count",
        "/api/v1/product-feedback", "/api/v1/channel-governance/overview",
        "/api/v1/reports/cluster/1", "/api/v1/reports/cluster/1/feedback", "/api/v1/reports/cluster/1/rca", "/api/v1/reports/feedback/1/rca-knowledge",
        "/api/v1/alerts", "/api/v1/alerts/1/ack", "/api/v1/alerts/1/transfer", "/api/v1/alerts/1/silence", "/api/v1/alerts/1/resolve",
        "/api/v1/import/batches", "/api/v1/import/upload", "/api/v1/import/upload/chunk",
        "/api/v1/import/upload/chunk/00000000-0000-0000-0000-000000000000/complete", "/api/v1/import/batches/B-1/confirm", "/api/v1/import/batches/B-1/rollback",
        "/api/v1/cluster/review", "/api/v1/cluster/review/batch",
        "/api/v1/ai-reviews", "/api/v1/ai-reviews/1", "/api/v1/ai-reviews/batch", "/api/v1/reports", "/api/v1/reports/generate",
        "/api/v1/admin/users", "/api/v1/admin/configs", "/api/v1/admin/knowledge", "/api/v1/admin/alert-rules",
        "/api/v1/admin/audit-logs", "/api/v1/admin/masking-rules", "/api/v1/masking-security/quarantine", "/api/v1/masking-security/close",
        "/api/v1/ingestion-operations/station", "/api/v1/ingestion-operations/station/retry", "/api/v1/admin/alert-rules/loop-health", "/actuator/metrics"
    )
    fun ok() = mapOf("ok" to true)
}
