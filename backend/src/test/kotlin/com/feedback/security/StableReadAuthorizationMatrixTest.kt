package com.feedback.security

import com.feedback.security.station.StationGatewayAuthenticationFilter

import com.feedback.config.WebSecurityConfig
import com.feedback.manager.sdk.SdkRequestTelemetryRecorder
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 校验稳定反馈读取/证据路由在真实过滤器链上的策略。 */
@WebMvcTest(controllers = [StableReadProbeController::class])
@Import(
    WebSecurityConfig::class,
    SecurityErrorWriter::class,
    AccessAuthorization::class,
    io.micrometer.core.instrument.simple.SimpleMeterRegistry::class
)
class StableReadAuthorizationMatrixTest {
    @Autowired lateinit var mvc: MockMvc

    @MockBean lateinit var webSessionFilter: WebSessionAuthenticationFilter
    @MockBean lateinit var auditFilter: RequestAuditFilter
    @MockBean lateinit var stationGatewayFilter: StationGatewayAuthenticationFilter
    @MockBean lateinit var sdkRequestTelemetryRecorder: SdkRequestTelemetryRecorder

    private val routes = listOf(
        HttpMethod.GET to "/api/v1/feedback",
        HttpMethod.GET to "/api/v1/feedback/export",
        HttpMethod.POST to "/api/v1/feedback/export",
        HttpMethod.GET to "/api/v1/feedback/7",
        HttpMethod.GET to "/api/v1/feedback/7/modifications",
        HttpMethod.GET to "/api/v1/feedback/7/timeline",
        HttpMethod.GET to "/api/v1/feedback/7/related",
        HttpMethod.GET to "/api/v1/feedback/7/attachments",
        HttpMethod.GET to "/api/v1/feedback/7/diagnostics",
        HttpMethod.POST to "/api/v1/feedback/7/diagnostics/authorize",
        HttpMethod.POST to "/api/v1/feedback/7/attachments/11/authorize",
        HttpMethod.POST to "/api/v1/feedback/7/attachments/11/authorize-preview",
        HttpMethod.POST to "/api/v1/feedback/7/attachments/authorize-previews",
        HttpMethod.GET to "/api/v1/feedback/7/attachments/11/stream",
        HttpMethod.GET to "/api/v1/feedback/7/attachments/11/preview",
        HttpMethod.GET to "/api/v1/feedback/7/external-link",
        HttpMethod.GET to "/api/v1/search/suggest",
        HttpMethod.GET to "/api/v1/dashboard/problem-analysis",
        HttpMethod.GET to "/api/v1/reports/cluster/3",
        HttpMethod.GET to "/api/v1/reports/cluster/3/feedback"
    )

    @BeforeEach
    fun passThroughApplicationFilters() {
        listOf(webSessionFilter, auditFilter, stationGatewayFilter).forEach { filter ->
            org.mockito.Mockito.doAnswer { invocation ->
                val chain = invocation.arguments[2] as FilterChain
                chain.doFilter(
                    invocation.arguments[0] as jakarta.servlet.ServletRequest,
                    invocation.arguments[1] as jakarta.servlet.ServletResponse
                )
                null
            }.`when`(filter).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
            )
        }
    }

    @Test
    fun `Feishu feedback product and technical principals may use stable reads`() {
        listOf("PHASE_ONE_PRODUCT", "PHASE_ONE_TECHNICAL").forEach { role ->
            val principal = principal(role, "SSO", setOf("access:phase_one_workspace"))
            routes.forEach { (method, path) ->
                mvc.perform(request(method, path).with(user(principal))).andExpect(status().isOk)
            }
        }
    }

    @Test
    fun `missing authentication missing permission and local identity are rejected`() {
        routes.forEach { (method, path) ->
            mvc.perform(request(method, path)).andExpect(status().isUnauthorized)
            mvc.perform(request(method, path).with(user(principal("PHASE_ONE_PRODUCT", "SSO", emptySet()))))
                .andExpect(status().isForbidden)
            mvc.perform(request(method, path).with(user(principal("PHASE_ONE_PRODUCT", "LOCAL", setOf("access:phase_one_workspace")))))
                .andExpect(status().isForbidden)
        }
    }

    private fun principal(role: String, provider: String, permissions: Set<String>) = WebPrincipal(
        99, "phase", "unused", "反馈用户", setOf(role), permissions, authProvider = provider
    )
}

@RestController
class StableReadProbeController {
    @RequestMapping(
        "/api/v1/feedback/7",
        "/api/v1/feedback",
        "/api/v1/feedback/export",
        "/api/v1/feedback/7/modifications",
        "/api/v1/feedback/7/timeline",
        "/api/v1/feedback/7/related",
        "/api/v1/feedback/7/attachments",
        "/api/v1/feedback/7/diagnostics",
        "/api/v1/feedback/7/diagnostics/authorize",
        "/api/v1/feedback/7/attachments/11/authorize",
        "/api/v1/feedback/7/attachments/11/authorize-preview",
        "/api/v1/feedback/7/attachments/authorize-previews",
        "/api/v1/feedback/7/attachments/11/stream",
        "/api/v1/feedback/7/attachments/11/preview",
        "/api/v1/feedback/7/external-link",
        "/api/v1/search/suggest",
        "/api/v1/dashboard/problem-analysis",
        "/api/v1/reports/cluster/3",
        "/api/v1/reports/cluster/3/feedback"
    )
    fun ok() = Unit
}
