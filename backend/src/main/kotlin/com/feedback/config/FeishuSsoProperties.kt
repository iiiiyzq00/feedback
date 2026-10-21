package com.feedback.config

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI

/**
 * 飞书 SSO 与组织同步的类型化配置入口。
 * 所有远程地址、凭据形态、有效期和超时在首次使用前集中校验，错误配置按失败关闭处理。
 */
@Component
@ConfigurationProperties("app.auth.feishu")
data class FeishuSsoProperties(
    var enabled: Boolean = false,
    var appId: String = "",
    var appSecret: String = "",
    var redirectUri: String = "",
    var publicBaseUrl: String = "",
    var bootstrapFirstUserAdmin: Boolean = false,
    var organizationSyncEnabled: Boolean = false,
    var organizationApiBaseUrl: String = "https://open.feishu.cn",
    var organizationSyncStaleMinutes: Long = 360,
    var organizationSyncRetryAttempts: Int = 3,
    var organizationSyncRetryDelayMs: Long = 25,
    var stateTtlSeconds: Long = 300,
    var loginCodeTtlSeconds: Long = 120,
    var oauthApiBaseUrl: String = "https://open.feishu.cn",
    var authorizeBaseUrl: String = "https://accounts.feishu.cn",
    var connectTimeoutSeconds: Long = 5,
    var readTimeoutSeconds: Long = 15,
    var callTimeoutSeconds: Long = 20,
    var allowHttpForLoopback: Boolean = false
) {
    /** 校验浏览器 OAuth 登录所需的开关、凭据、地址、时限与超时关系。 */
    fun validateEnabled() {
        configured(enabled, "FEISHU_SSO_DISABLED")
        configured(appId.startsWith("cli_") && appSecret.isNotBlank(), "FEISHU_SSO_CREDENTIALS_MISSING")
        configured(validBrowserUrl(redirectUri), "FEISHU_SSO_REDIRECT_URI_INVALID")
        configured(validBrowserUrl(publicBaseUrl), "FEISHU_SSO_PUBLIC_BASE_URL_INVALID")
        configured(validRemoteBase(oauthApiBaseUrl), "FEISHU_OAUTH_API_BASE_URL_INVALID")
        configured(validRemoteBase(authorizeBaseUrl), "FEISHU_AUTHORIZE_BASE_URL_INVALID")
        configured(stateTtlSeconds in 60..600, "FEISHU_STATE_TTL_INVALID")
        configured(loginCodeTtlSeconds in 60..300, "FEISHU_LOGIN_CODE_TTL_INVALID")
        configured(
            connectTimeoutSeconds in 1..30 &&
                readTimeoutSeconds in connectTimeoutSeconds..120 &&
                callTimeoutSeconds in readTimeoutSeconds..180,
            "FEISHU_TIMEOUT_INVALID"
        )
    }

    /** 在基础 SSO 配置之上继续校验组织事实同步专用参数。 */
    fun validateOrganizationEnabled() {
        validateEnabled()
        configured(organizationSyncEnabled, "FEISHU_ORGANIZATION_SYNC_DISABLED")
        configured(validRemoteBase(organizationApiBaseUrl), "FEISHU_ORGANIZATION_API_BASE_URL_INVALID")
        configured(organizationSyncStaleMinutes in 30..1440, "FEISHU_ORGANIZATION_SYNC_STALE_WINDOW_INVALID")
        configured(organizationSyncRetryAttempts in 1..5, "FEISHU_ORGANIZATION_SYNC_RETRY_ATTEMPTS_INVALID")
        configured(organizationSyncRetryDelayMs in 0..1000, "FEISHU_ORGANIZATION_SYNC_RETRY_DELAY_INVALID")
    }

    /** 浏览器回跳地址允许 HTTP(S)，但拒绝用户信息和片段等非预期 URI 成分。 */
    private fun validBrowserUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null
    }

    /** 远端 API 默认必须 HTTPS；仅显式配置时允许回环地址使用 HTTP 便于本地测试。 */
    private fun validRemoteBase(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
        val schemeAllowed = uri.scheme == "https" || (allowHttpForLoopback && loopback && uri.scheme == "http")
        return schemeAllowed && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")
    }

    /** 将配置校验失败统一转换为可观测的飞书配置异常。 */
    private fun configured(condition: Boolean, reasonCode: String) {
        if (!condition) {
            throw ServiceException(ErrorCode.FEISHU_CONFIGURATION_INVALID, reasonCode)
        }
    }
}
