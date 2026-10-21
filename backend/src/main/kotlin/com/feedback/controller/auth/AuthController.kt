package com.feedback.controller.auth

import com.feedback.common.ApiResponse
import com.feedback.common.exception.ClientException
import com.feedback.dto.auth.FeishuLoginCodeRequest
import com.feedback.dto.auth.LoginRequest
import com.feedback.dto.auth.RegisterRequest
import com.feedback.security.LoginProtection
import com.feedback.security.PrincipalAccess
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import com.feedback.security.WebSessionCookieService
import com.feedback.service.auth.AuthSessionLifecycleService
import com.feedback.model.auth.AuthenticatedUserResult
import com.feedback.service.auth.AuthRegistrationService
import com.feedback.service.auth.FeishuSsoService
import com.feedback.service.auth.LocalAuthenticationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** 本地账号密码登录请求；空值在进入认证管理器前由 Bean Validation 拒绝。 */
/**
 * Web 认证入口。
 *
 * 控制器依赖 [LoginProtection] 契约，使认证流程不需要了解失败计数存储和安全审计的实现细节。
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val localAuthentication: LocalAuthenticationService,
    private val registrations: AuthRegistrationService,
    private val sessions: AuthSessionLifecycleService,
    private val loginProtection: LoginProtection,
    private val feishu: FeishuSsoService,
    private val sessionCookies: WebSessionCookieService
) {
    /**
     * POST /auth/login：校验限流状态和账号密码，记录登录结果，并签发 Cookie 会话。
     * 成功响应只返回前端展示和鉴权所需的用户快照，不暴露认证凭据。
     */
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        csrfToken: CsrfToken
    ): ApiResponse<Map<String, Any?>> {
        val clientIp = servletRequest.remoteAddr
        loginProtection.assertAllowed(request.username, clientIp)
        val user = try {
            localAuthentication.authenticate(request.username, request.password, clientIp)
        } catch (_: AuthenticationException) {
            loginProtection.recordFailure(request.username, clientIp)
            throw BadCredentialsException("AUTH_INVALID_CREDENTIALS")
        }

        // 数据库成功事务已提交后再访问 Redis，避免会话网络 I/O 延长数据库事务。
        loginProtection.resetAfterSuccess(user.username)
        sessionCookies.establish(servletRequest, servletResponse, user.userId, user.authVersion)
        csrfToken.token // 强制 CSRF 仓储建立可读的双提交 Cookie。
        return ApiResponse.success(mapOf("user" to view(user)))
    }

    /**
     * POST /auth/register：本地自助注册（入口默认关闭，见 LocalRegistrationProperties）。
     *
     * 不在这里直接签发会话：注册只建档，随后由调用方走同一套 /auth/login 完成认证，
     * 使会话建立路径、失败计数与审计口径只有一份。限流复用登录入口的计数器，
     * 因此同一 IP 的注册尝试与登录尝试共享同一预算。
     */
    @PostMapping("/register")
    fun register(
        @Valid @RequestBody request: RegisterRequest,
        servletRequest: HttpServletRequest,
        csrfToken: CsrfToken
    ): ApiResponse<Map<String, Any?>> {
        val clientIp = servletRequest.remoteAddr
        loginProtection.assertAllowed(request.username, clientIp)
        val userId = try {
            registrations.register(request.username, request.password, request.displayName, clientIp)
        } catch (error: ClientException) {
            loginProtection.recordFailure(request.username, clientIp)
            throw error
        }
        loginProtection.resetAfterSuccess(request.username.trim())
        csrfToken.token // 与登录一致：强制 CSRF 仓储建立可读的双提交 Cookie。
        return ApiResponse.success(mapOf("id" to userId, "username" to request.username.trim()))
    }

    /** GET /auth/me：从现有会话恢复最新用户、角色和权限，同时确保 CSRF Cookie 已生成。 */
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal principal: WebPrincipal,
        csrfToken: CsrfToken
    ): ApiResponse<Map<String, Any?>> {
        // 会话可跨后端重启继续存在，而可读 CSRF Cookie 可能缺失；读取 token 可在首个写请求前修复它。
        csrfToken.token
        // 认证过滤器已按当前会话装配最新主体，此处复用快照，避免同一请求再次查询用户授权。
        return ApiResponse.success(view(principal))
    }

    /** GET /auth/csrf：为尚无 CSRF Cookie 的浏览器显式初始化双提交 token。 */
    @GetMapping("/csrf")
    fun csrf(csrfToken: CsrfToken): ApiResponse<Map<String, String>> =
        ApiResponse.success(mapOf("token" to csrfToken.token))

    /** POST /auth/logout：提升用户会话版本使旧会话失效，并清除当前浏览器的会话 Cookie。 */
    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal principal: WebPrincipal,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ApiResponse<Map<String, Boolean>> {
        sessions.invalidate(principal.userId)
        sessionCookies.clear(request, response)
        return ApiResponse.success(mapOf("loggedOut" to true))
    }

    /** POST /auth/feishu/authorize：创建一次性 state 并返回飞书授权页地址。 */
    @PostMapping("/feishu/authorize")
    fun feishuAuthorize(
        @RequestParam(required = false) returnPath: String?,
        servletRequest: HttpServletRequest
    ) = ApiResponse.success(feishu.authorize(returnPath, servletRequest.getHeader(HttpHeaders.ORIGIN)))

    /** GET /auth/feishu/callback：接收飞书回调，完成远程换票后重定向到前端登录页。 */
    @GetMapping("/feishu/callback")
    fun feishuCallback(
        @RequestParam code: String,
        @RequestParam state: String
    ): ResponseEntity<Void> {
        val redirect = feishu.callback(code, state).redirectUrl
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, URI.create(redirect).toASCIIString())
            .build()
    }

    /** POST /auth/feishu/exchange：消费前端收到的一次性短码，并建立正式 Cookie 会话。 */
    @PostMapping("/feishu/exchange")
    fun feishuExchange(
        @Valid @RequestBody request: FeishuLoginCodeRequest,
        servletRequest: HttpServletRequest,
        response: HttpServletResponse,
        csrfToken: CsrfToken
    ): ApiResponse<Map<String, Any?>> {
        val login = feishu.exchangeLoginCode(request.code)
        // 短码兑换事务已经返回会话版本，不在事务提交后重复查询用户。
        sessionCookies.establish(servletRequest, response, login.userId, login.authVersion)
        csrfToken.token
        return ApiResponse.success(mapOf("user" to login.user, "returnPath" to login.returnPath))
    }

    /** 将安全主体收敛为稳定的前端用户契约，避免泄露密码摘要或会话版本等内部字段。 */
    private fun view(principal: WebPrincipal): Map<String, Any?> = mapOf(
        "id" to principal.userId,
        "username" to principal.username,
        "realName" to (principal.displayName ?: principal.username),
        "role" to (principal.roles.firstOrNull() ?: RoleCode.USER).code,
        "roles" to principal.roles.map { it.code }.sorted(),
        "permissions" to principal.permissions.map { it.code }.sorted(),
        "authProvider" to principal.authProvider.code,
        // 前端路由守卫要用同一份判定：本地工作区放宽开关打开时，LOCAL 主体也能进入工作区页面。
        // 页面可见性不是访问控制边界，权威判定始终在服务端（PrincipalAccess）。
        "workspaceAccessRelaxed" to PrincipalAccess.localWorkspaceEnabled,
    )

    /** 将本地认证内部结果转换为同一稳定 HTTP 用户契约。 */
    private fun view(user: AuthenticatedUserResult): Map<String, Any?> = mapOf(
        "id" to user.userId,
        "username" to user.username,
        "realName" to (user.displayName ?: user.username),
        "role" to (user.roles.firstOrNull() ?: RoleCode.USER).code,
        "roles" to user.roles.map { it.code }.sorted(),
        "permissions" to user.permissions.map { it.code }.sorted(),
        "authProvider" to user.authProvider.code,
        "workspaceAccessRelaxed" to PrincipalAccess.localWorkspaceEnabled,
    )
}
