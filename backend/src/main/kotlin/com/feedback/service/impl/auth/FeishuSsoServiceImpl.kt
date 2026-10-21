package com.feedback.service.impl.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.config.FeishuSsoProperties
import com.feedback.repository.auth.FeishuSsoRepository
import com.feedback.repository.remote.auth.FeishuOAuthGateway
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import com.feedback.security.WebUserDetailsService
import com.feedback.model.auth.FeishuAuthorizeResult
import com.feedback.model.auth.FeishuCallbackResult
import com.feedback.model.auth.FeishuLoginResult
import com.feedback.service.auth.FeishuSsoService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 飞书浏览器登录服务实现。
 *
 * 远程 OAuth 交换始终发生在数据库事务之外；state 的并发消费和身份绑定由
 * [FeishuSsoRepository] 在短事务中完成，避免远程依赖延迟占用数据库连接或行锁。
 */
@Service
class FeishuSsoServiceImpl(
    private val properties: FeishuSsoProperties,
    private val client: FeishuOAuthGateway,
    private val store: FeishuSsoRepository,
    private val users: WebUserDetailsService,
) : FeishuSsoService {
    private val random = SecureRandom()
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 创建带有效期的一次性 OAuth state，并组装飞书授权地址。
     * returnPath 和实际 redirectUri 一同落库，回调时不信任浏览器重新提交的地址。
     */
    override fun authorize(returnPath: String?, requestOrigin: String?): FeishuAuthorizeResult {
        properties.validateEnabled()
        val safeReturn = safeReturnPath(returnPath)
        val redirectUri = redirectUriFor(requestOrigin)
        val state = randomToken()
        store.createState(
            hash(state),
            safeReturn,
            redirectUri,
            properties.stateTtlSeconds.coerceIn(60, 600),
        )
        val url = UriComponentsBuilder
            .fromUriString(properties.authorizeBaseUrl.trimEnd('/') + "/open-apis/authen/v1/authorize")
            .queryParam("client_id", properties.appId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("state", state)
            .queryParam("scope", "contact:user.base:readonly offline_access")
            .build()
            .encode()
            .toUriString()
        return FeishuAuthorizeResult(url)
    }

    /**
     * 校验并锁定 OAuth state，调用飞书换取身份，再签发供前端兑换的一次性短码。
     * 远端超时会转换为安全的前端错误跳转，不把内部依赖异常直接暴露给浏览器。
     */
    override fun callback(code: String, state: String): FeishuCallbackResult {
        properties.validateEnabled()
        validateOneTimeValue(code, "FEISHU_AUTHORIZATION_CODE_INVALID")
        validateOneTimeValue(state, "FEISHU_STATE_INVALID")
        val stateHash = hash(state)
        val pending = store.findPendingState(stateHash)
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "FEISHU_STATE_INVALID_OR_EXPIRED")
        val redirectUri = pending.redirectUri.takeIf(String::isNotBlank) ?: properties.redirectUri

        val identity = try {
            client.exchange(code, redirectUri)
        } catch (error: RemoteException) {
            log.warn(
                "Feishu callback dependency failure: errorCode={}, reasonCode={}, retryable={}",
                error.errorCode.name,
                error.reasonCode,
                error.errorCode.retryable,
            )
            return FeishuCallbackResult(callbackFailureRedirect(redirectUri, "provider_timeout"))
        }

        val loginCode = randomToken()
        store.completeCallback(
            stateHash,
            identity,
            hash(loginCode),
            properties.loginCodeTtlSeconds.coerceIn(60, 300),
        )
        val redirect = UriComponentsBuilder.fromUriString(originOf(redirectUri) + "/login")
            .queryParam("feishu_code", loginCode)
            .build()
            .encode()
            .toUriString()
        return FeishuCallbackResult(redirect)
    }

    /**
     * 消费本地一次性登录码并记录登录时间，两步必须共同提交或回滚。
     * 事务只包含本地数据库读取与写入，不包含飞书 HTTP、Redis 或 Cookie 操作。
     */
    @Transactional
    override fun exchangeLoginCode(code: String): FeishuLoginResult {
        validateOneTimeValue(code, "FEISHU_LOGIN_CODE_INVALID")
        val login = store.consumeLoginCode(hash(code))
        val principal = users.loadUserById(login.userId)
        store.updateLastLogin(login.userId)
        return FeishuLoginResult(principal.userId, principal.authVersion, view(principal), login.returnPath)
    }

    /** 只允许站内绝对路径，阻断以 returnPath 构造开放重定向。 */
    private fun safeReturnPath(value: String?): String {
        val candidate = value?.trim().orEmpty()
        return if (candidate.startsWith('/') && !candidate.startsWith("//") && candidate.length <= 500) {
            candidate
        } else {
            "/"
        }
    }

    /**
     * 浏览器同源请求的 Origin 可能和反向代理后的 Host 不同。此处只接受纯 HTTP(S)
     * origin；飞书控制台登记的回调地址仍是最终允许列表，异常输入退回部署配置。
     */
    private fun redirectUriFor(requestOrigin: String?): String {
        val origin = requestOrigin?.trim()?.takeIf { it.length <= 240 } ?: return properties.redirectUri
        val uri = runCatching { URI(origin) }.getOrNull() ?: return properties.redirectUri
        if (
            uri.scheme !in setOf("http", "https") ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null ||
            (uri.rawPath.isNotEmpty() && uri.rawPath != "/")
        ) {
            return properties.redirectUri
        }
        val normalizedOrigin = URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null)
            .toASCIIString()
            .trimEnd('/')
        return "$normalizedOrigin/api/v1/auth/feishu/callback"
    }

    /** 从可信回调地址提取规范化源站，用于拼接前端登录页。 */
    private fun originOf(redirectUri: String): String {
        val uri = URI(redirectUri)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw ClientException(ErrorCode.BAD_REQUEST, "FEISHU_SSO_REDIRECT_URI_INVALID")
        }
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null)
            .toASCIIString()
            .trimEnd('/')
    }

    /** 构造仅含公开错误码的登录页地址。 */
    private fun callbackFailureRedirect(redirectUri: String, error: String): String =
        UriComponentsBuilder.fromUriString(originOf(redirectUri) + "/login")
            .queryParam("feishu_error", error)
            .build()
            .encode()
            .toUriString()

    /** 在哈希或远程调用前限制一次性凭证的空值与长度。 */
    private fun validateOneTimeValue(value: String, reasonCode: String) {
        if (value.isBlank() || value.length > 2048) {
            throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)
        }
    }

    /** 生成具有 256 位随机性的 URL-safe state 或登录短码。 */
    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    /** 数据库只保存一次性值的 SHA-256 摘要，避免明文泄露后被直接重放。 */
    private fun hash(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    /** 生成前端鉴权所需的最小用户视图。 */
    private fun view(principal: WebPrincipal): Map<String, Any?> = mapOf(
        "id" to principal.userId,
        "username" to principal.username,
        "realName" to (principal.displayName ?: principal.username),
        "role" to (principal.roles.firstOrNull() ?: RoleCode.USER).code,
        "roles" to principal.roles.map { it.code }.sorted(),
        "permissions" to principal.permissions.map { it.code }.sorted(),
        "authProvider" to principal.authProvider.code,
    )
}
