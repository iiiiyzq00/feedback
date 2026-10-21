package com.feedback.security

import com.feedback.config.WebSecurityConfig
import com.feedback.manager.sdk.SdkRequestTelemetryRecorder
import com.feedback.security.station.StationGatewayAuthenticationFilter
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

/** 使用正式 SecurityFilterChain 验证应用身份管理入口只能由管理员访问。 */
@WebMvcTest(controllers = [ApplicationIdentityAuthorizationProbeController::class])
@Import(
    WebSecurityConfig::class,
    SecurityErrorWriter::class,
    AccessAuthorization::class,
    io.micrometer.core.instrument.simple.SimpleMeterRegistry::class
)
class ApplicationIdentityAuthorizationTest {
    @Autowired lateinit var mvc: MockMvc

    @MockBean lateinit var webSessionFilter: WebSessionAuthenticationFilter
    @MockBean lateinit var auditFilter: RequestAuditFilter
    @MockBean lateinit var stationGatewayFilter: StationGatewayAuthenticationFilter
    @MockBean lateinit var sdkRequestTelemetryRecorder: SdkRequestTelemetryRecorder

    private val routes = listOf(
        HttpMethod.GET to "/api/v1/admin/application-identities/diagnostics",
        HttpMethod.GET to "/api/v1/admin/application-identities",
        HttpMethod.GET to "/api/v1/admin/application-identities/11111111-1111-4111-8111-111111111111/aliases",
        HttpMethod.POST to "/api/v1/admin/application-identities/shadow-batches",
        HttpMethod.POST to "/api/v1/admin/application-identities/reviewed-bindings",
        HttpMethod.POST to "/api/v1/admin/application-identities/11111111-1111-4111-8111-111111111111/aliases/rebind"
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
    fun `administrator succeeds while unauthenticated and non administrator callers are rejected`() {
        val admin = principal("ADMIN")
        val operator = principal("OPERATOR")
        routes.forEach { (method, path) ->
            mvc.perform(request(method, path).with(user(admin))).andExpect(status().isOk)
            mvc.perform(request(method, path).with(user(operator))).andExpect(status().isForbidden)
            mvc.perform(request(method, path)).andExpect(status().isUnauthorized)
        }
    }

    private fun principal(role: String) = WebPrincipal(
        99, role.lowercase(), "unused", role, setOf(role), emptySet(), authProvider = "LOCAL"
    )
}

@RestController
class ApplicationIdentityAuthorizationProbeController {
    @RequestMapping(
        "/api/v1/admin/application-identities/diagnostics",
        "/api/v1/admin/application-identities",
        "/api/v1/admin/application-identities/11111111-1111-4111-8111-111111111111/aliases",
        "/api/v1/admin/application-identities/shadow-batches",
        "/api/v1/admin/application-identities/reviewed-bindings",
        "/api/v1/admin/application-identities/11111111-1111-4111-8111-111111111111/aliases/rebind"
    )
    fun ok() = Unit
}
