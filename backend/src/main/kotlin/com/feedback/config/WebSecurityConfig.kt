package com.feedback.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.feedback.security.WebSessionAuthenticationFilter
import com.feedback.security.RequestAuditFilter
import com.feedback.security.SecurityErrorWriter
import com.feedback.security.station.StationGatewayAuthenticationFilter
import com.feedback.security.AccessAuthorization
import com.feedback.security.WEB_SESSION_COOKIE
import com.feedback.model.auth.PlatformPermissions
import com.feedback.model.auth.RoleCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * Web 安全配置 — SDK + Web 双通道认证
 * SDK 端：HMAC-SHA256 签名，由 SdkAuthFilter 处理
 * Web 端：Redis 不透明会话，由 WebSessionAuthenticationFilter 处理
 */
@Configuration
@EnableWebSecurity
class WebSecurityConfig(
    private val webSessionFilter: WebSessionAuthenticationFilter,
    private val auditFilter: RequestAuditFilter,
    private val stationGatewayFilter: StationGatewayAuthenticationFilter,
    private val securityErrors: SecurityErrorWriter,
    private val feedbackAccess: AccessAuthorization,
    @param:Value("\${app.auth.session-cookie.secure:true}") private val secureSessionCookie: Boolean,
    @param:Value("\${app.auth.session-cookie.same-site:Strict}") private val sessionCookieSameSite: String
) {

    /** 本地密码统一使用成本因子 12 的 BCrypt 校验。 */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    /** 暴露 Spring 根据 UserDetailsService 和 PasswordEncoder 组装的认证管理器。 */
    @Bean
    fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager =
        configuration.authenticationManager

    /**
     * 组装无状态 Spring Security 过滤器链、Cookie 会话 CSRF 规则及接口授权矩阵。
     * Web、学习机网关与其他签名通道各自认证，最终共享同一授权决策链。
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
            setCookiePath("/")
            setCookieCustomizer { cookie ->
                cookie.secure(secureSessionCookie).sameSite(sessionCookieSameSite)
            }
        }
        http
            .csrf { csrf ->
                csrf.csrfTokenRepository(csrfRepository)
                // Axios 把 Cookie 中的原始双提交 token 放进 X-XSRF-TOKEN，不使用表单场景的 XOR 编码。
                csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                // 仅浏览器 Cookie 会话需要 CSRF；SDK、学习机网关和 provider 使用独立的签名认证。
                csrf.requireCsrfProtectionMatcher(RequestMatcher { request ->
                    val unsafeMethod = request.method !in setOf("GET", "HEAD", "OPTIONS", "TRACE")
                    val hasBrowserSession = request.cookies?.any {
                        it.name == WEB_SESSION_COOKIE && it.value.isNotBlank()
                    } == true
                    val authenticationBootstrap = request.requestURI == "/api/v1/auth/login" ||
                        request.requestURI.startsWith("/api/v1/auth/feishu/")
                    unsafeMethod && hasBrowserSession && !authenticationBootstrap
                })
            }
            .headers { headers ->
                headers.frameOptions { it.deny() }
                headers.contentTypeOptions { }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                headers.permissionsPolicyHeader {
                    it.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()")
                }
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'") }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    if (request.getAttribute("WEB_SESSION_AUTHENTICATED") == true) {
                        securityErrors.forbidden(response)
                    } else {
                        securityErrors.unauthorized(response)
                    }
                }
                it.accessDeniedHandler { _, response, _ -> securityErrors.forbidden(response) }
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.POST, "/v1/feedback", "/v1/feedback/new").hasAuthority(PlatformPermissions.STATION_INGEST.code)
                // /api/v1/auth/register 只在 app.auth.registration.enabled=true 时才真正可用；
                // 关闭时服务层按 404 处理，因此这里放开认证不等于放开功能。
                auth.requestMatchers("/actuator/health", "/actuator/info", "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/feishu/**", "/api/v1/sdk/**", "/api/v1/provider/notification-receipts", "/api/v1/provider/security-events").permitAll()
                auth.requestMatchers("/actuator/**").hasRole(RoleCode.ADMIN.code)
                auth.requestMatchers(HttpMethod.GET,"/api/v1/admin/audit-logs").hasAuthority(PlatformPermissions.AUDIT_LOGS.code)
                auth.requestMatchers(HttpMethod.GET,"/api/v1/admin/audit-integrity").hasAuthority(PlatformPermissions.AUDIT_LOGS.code)
                auth.requestMatchers(HttpMethod.GET,"/api/v1/admin/masking-rules/**").hasAuthority(PlatformPermissions.MASKING_VIEW.code)
                auth.requestMatchers(HttpMethod.POST,"/api/v1/admin/masking-rules/test").hasAuthority(PlatformPermissions.MASKING_VIEW.code)
                auth.requestMatchers(HttpMethod.PUT,"/api/v1/admin/masking-rules/**").hasAuthority(PlatformPermissions.MASKING_EDIT.code)
                auth.requestMatchers(HttpMethod.POST,"/api/v1/admin/masking-rules/**").hasAuthority(PlatformPermissions.MASKING_EDIT.code)
                auth.requestMatchers("/api/v1/admin/users/**","/api/v1/admin/roles/**").hasAuthority(PlatformPermissions.USER_MANAGEMENT.code)
                auth.requestMatchers(HttpMethod.GET,"/api/v1/admin/alert-rules/loop-health").hasAuthority(PlatformPermissions.ALERT_LOOP_HEALTH.code)
                auth.requestMatchers("/api/v1/admin/configs/**").hasAuthority(PlatformPermissions.SYSTEM_CONFIG.code)
                auth.requestMatchers(HttpMethod.GET,"/api/v1/admin/alert-rules/oncall/**").hasAuthority(PlatformPermissions.ONCALL_VIEW.code)
                auth.requestMatchers(HttpMethod.POST,"/api/v1/admin/alert-rules/oncall/**").hasAnyAuthority(PlatformPermissions.ONCALL_EDIT_OWN.code,PlatformPermissions.ONCALL_EDIT_ALL.code)
                auth.requestMatchers(HttpMethod.DELETE,"/api/v1/admin/alert-rules/oncall/**").hasAnyAuthority(PlatformPermissions.ONCALL_EDIT_OWN.code,PlatformPermissions.ONCALL_EDIT_ALL.code)
                auth.requestMatchers("/api/v1/admin/alert-rules/**").hasAuthority(PlatformPermissions.ROUTING_CONFIG.code)
                auth.requestMatchers(HttpMethod.POST,"/api/v1/admin/knowledge/jira-candidates/generate").hasAuthority(PlatformPermissions.GENERATE_JIRA_KNOWLEDGE_CANDIDATES.code)
                auth.requestMatchers("/api/v1/admin/knowledge/**").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers("/api/v1/admin/**").hasRole(RoleCode.ADMIN.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/masking-security/quarantine").hasAuthority(PlatformPermissions.MASKING_QUARANTINE.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/ingestion-operations/**").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/ingestion-operations/station/retry").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/masking-security/contacts/**").hasRole(RoleCode.ADMIN.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/masking-security/raw-inspection").hasAuthority(PlatformPermissions.INSPECT_MASKING_RAW.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/masking-security/retry").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/masking-security/close").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/alerts/**").hasAuthority(PlatformPermissions.ALERT_CENTER.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/alerts/**").hasAuthority(PlatformPermissions.ACK_ALERT.code)
                auth.requestMatchers("/api/v1/in-app-notifications/**").access(feedbackAccess.outsideWorkspace())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/import/batches/*/retry").hasRole(RoleCode.ADMIN.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/import/batches/*/retry-technical").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/import/batches/*/rollback").hasRole(RoleCode.ADMIN.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/import/batches/governance/cleanup").hasRole(RoleCode.ADMIN.code)
                auth.requestMatchers(HttpMethod.DELETE, "/api/v1/import/batches/*/rollback").hasRole(RoleCode.ADMIN.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/import/batches/*/export").hasAuthority(PlatformPermissions.FEEDBACK_EXPORT.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/import/jira/sync").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/import/jira/log-attachments/pull").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/import/jira/fault-time/re-pull").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/import/upload", "/api/v1/import/upload/chunk", "/api/v1/import/upload/chunk/*/complete",
                    "/api/v1/import/attachments/*", "/api/v1/import/batches/*/confirm").hasAuthority(PlatformPermissions.UPLOAD_FILE.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/import/**").hasAuthority(PlatformPermissions.IMPORT_MANAGEMENT.code)
                auth.requestMatchers("/api/v1/import/**", "/api/v1/cluster/**").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/dashboard/problem-analysis").access(feedbackAccess.analysis())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/dashboard/**").hasAuthority(PlatformPermissions.DASHBOARD_ANALYSIS.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/dashboard/**").hasAuthority(PlatformPermissions.DASHBOARD_READ.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/ai-reviews/**").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/ai-reviews/**").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers("/api/v1/reports/rca/**").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/reports/cluster/*", "/api/v1/reports/cluster/*/feedback").access(feedbackAccess.clusterDetail())
                auth.requestMatchers(HttpMethod.GET, "/api/v1/reports/feedback/*/rca-knowledge").hasAuthority(PlatformPermissions.AI_REPORTS.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/reports/**").hasAuthority(PlatformPermissions.AI_REPORTS.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/reports/generate").hasAuthority(PlatformPermissions.GENERATE_REPORTS.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/reports/tasks/*/retry").hasAuthority(PlatformPermissions.GENERATE_REPORTS.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/reports/tasks/*/cancel").hasAuthority(PlatformPermissions.GENERATE_REPORTS.code)
                auth.requestMatchers(HttpMethod.DELETE, "/api/v1/reports/*").hasAuthority(PlatformPermissions.DELETE_REPORTS.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/reports/**").hasAuthority(PlatformPermissions.AI_REPORTS.code)
                auth.requestMatchers(HttpMethod.GET, "/api/v1/feedback/export").access(feedbackAccess.channelExport())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/feedback/export").access(feedbackAccess.channelExport())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/feedback/*/attachments/*/authorize-original-download").access(feedbackAccess.channel())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/feedback/*/attachments/*/authorize-preview").access(feedbackAccess.channel())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/feedback/*/attachments/authorize-previews").access(feedbackAccess.channel())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/feedback/*/attachments/*/authorize").access(feedbackAccess.channel())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/feedback/*/diagnostics/authorize").access(feedbackAccess.channel())
                auth.requestMatchers(HttpMethod.GET, "/api/v1/feedback/my-tasks", "/api/v1/feedback/eligible-owners", "/api/v1/feedback/*/disposition-history").access(feedbackAccess.outsideWorkspace())
                auth.requestMatchers(HttpMethod.GET, "/api/v1/feedback/*/attachments/*/original").access(feedbackAccess.channel())
                auth.requestMatchers(HttpMethod.GET, "/api/v1/feedback/**", "/api/v1/search/**").access(feedbackAccess.channel())
                auth.requestMatchers(HttpMethod.PUT, "/api/v1/feedback/**").hasAuthority(PlatformPermissions.EDIT_STATUS.code)
                auth.requestMatchers(HttpMethod.POST, "/api/v1/feedback/**").access(feedbackAccess.outsideWorkspace())
                auth.requestMatchers("/api/v1/product-feedback/**", "/api/v1/channel-governance/**").access(feedbackAccess.outsideWorkspace())
                auth.requestMatchers(HttpMethod.POST, "/api/v1/cluster/**").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.requestMatchers(HttpMethod.DELETE, "/api/v1/cluster/**").hasAnyRole(RoleCode.ADMIN.code, RoleCode.OPERATOR.code)
                auth.anyRequest().authenticated()
            }
            .addFilterBefore(webSessionFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterAfter(stationGatewayFilter, WebSessionAuthenticationFilter::class.java)
            .addFilterAfter(auditFilter, WebSessionAuthenticationFilter::class.java)
        return http.build()
    }
}
