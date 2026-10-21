package com.feedback.repository.remote.auth

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.config.FeishuSsoProperties
import com.feedback.model.result.auth.FeishuIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * 飞书官方 OAuth HTTP 网关实现。
 *
 * 依次调用授权码换 token 与用户信息接口，将 HTTP/业务错误统一转换为平台远程异常；
 * access token 只存在于当前调用内存中，既不返回给上层，也不写入日志或数据库。
 */
@Component
class OfficialFeishuOAuthGateway(
    private val properties: FeishuSsoProperties,
    private val mapper: ObjectMapper,
) : FeishuOAuthGateway {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
            .readTimeout(Duration.ofSeconds(properties.readTimeoutSeconds))
            .callTimeout(Duration.ofSeconds(properties.callTimeoutSeconds))
            .build()
    }
    private val json = "application/json; charset=utf-8".toMediaType()

    /** 交换 access token 后立即读取用户资料，并只返回身份绑定所需的字段。 */
    override fun exchange(code: String, redirectUri: String): FeishuIdentity {
        properties.validateEnabled()
        if (code.isBlank() || code.length > 2048) {
            throw ClientException(ErrorCode.BAD_REQUEST, "FEISHU_AUTHORIZATION_CODE_INVALID")
        }
        val base = properties.oauthApiBaseUrl.trimEnd('/')
        val tokenBody = mapper.writeValueAsBytes(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to properties.appId,
                "client_secret" to properties.appSecret,
                "code" to code,
                "redirect_uri" to redirectUri,
            ),
        ).toRequestBody(json)
        val tokenRequest = Request.Builder()
            .url("$base/open-apis/authen/v2/oauth/token")
            .post(tokenBody)
            .header("Accept", "application/json")
            .build()
        val tokenRoot = execute(tokenRequest, "FEISHU_TOKEN_EXCHANGE_IO_FAILED")
        requireSuccessCode(tokenRoot, "FEISHU_TOKEN_EXCHANGE_REJECTED")
        val accessToken = tokenRoot.path("access_token").asText().takeIf(String::isNotBlank)
            ?: throw remote(ErrorCode.FEISHU_RESPONSE_INVALID, "FEISHU_ACCESS_TOKEN_MISSING")

        val userRequest = Request.Builder()
            .url("$base/open-apis/authen/v1/user_info")
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        val userRoot = execute(userRequest, "FEISHU_USER_INFO_IO_FAILED")
        requireSuccessCode(userRoot, "FEISHU_USER_INFO_REJECTED")
        val data = userRoot.path("data")
        if (!data.isObject) {
            throw remote(ErrorCode.FEISHU_RESPONSE_INVALID, "FEISHU_USER_INFO_DATA_MISSING")
        }
        return FeishuIdentity(
            openId = data.path("open_id").asText().takeIf(String::isNotBlank)
                ?: throw remote(ErrorCode.FEISHU_RESPONSE_INVALID, "FEISHU_OPEN_ID_MISSING"),
            unionId = data.path("union_id").asText().takeIf(String::isNotBlank),
            employeeId = data.path("user_id").asText().takeIf(String::isNotBlank),
            tenantKey = data.path("tenant_key").asText().takeIf(String::isNotBlank),
            name = data.path("name").asText().takeIf(String::isNotBlank) ?: "飞书用户",
            avatarUrl = data.path("avatar_url").asText().takeIf(String::isNotBlank),
        )
    }

    /** 执行请求并清除可能包含 token 的原始响应字节。 */
    private fun execute(request: Request, ioReason: String): JsonNode {
        try {
            http.newCall(request).execute().use { response ->
                mapStatus(response.code, response.header("Retry-After"))
                val bytes = response.body?.bytes()
                    ?: throw remote(ErrorCode.FEISHU_RESPONSE_INVALID, "FEISHU_RESPONSE_EMPTY")
                return try {
                    try {
                        mapper.readTree(bytes)
                    } catch (_: JacksonException) {
                        throw remote(ErrorCode.FEISHU_RESPONSE_INVALID, "FEISHU_RESPONSE_MALFORMED")
                    }
                } finally {
                    bytes.fill(0)
                }
            }
        } catch (error: RemoteException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw remote(ErrorCode.REMOTE_TIMEOUT, "FEISHU_REMOTE_TIMEOUT", cause = error)
        } catch (error: IOException) {
            throw remote(ErrorCode.FEISHU_UNAVAILABLE, ioReason, cause = error)
        }
    }

    /** 将 HTTP 状态映射为稳定的平台错误语义，并保留上游限流等待建议。 */
    private fun mapStatus(status: Int, retryAfter: String?) {
        when (status) {
            in 200..299 -> return
            401, 403 -> throw remote(ErrorCode.FEISHU_AUTHENTICATION_FAILED, "FEISHU_HTTP_$status")
            408, 504 -> throw remote(ErrorCode.REMOTE_TIMEOUT, "FEISHU_REMOTE_TIMEOUT")
            429 -> throw remote(
                ErrorCode.FEISHU_RATE_LIMITED,
                "FEISHU_RATE_LIMITED",
                mapOf("retryAfterSeconds" to (retryAfter?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L)),
            )
            in 400..499 -> throw remote(
                ErrorCode.FEISHU_REQUEST_REJECTED,
                "FEISHU_HTTP_$status",
                mapOf("upstreamStatus" to status),
            )
            else -> throw remote(
                ErrorCode.FEISHU_UNAVAILABLE,
                "FEISHU_HTTP_$status",
                mapOf("upstreamStatus" to status),
            )
        }
    }

    /** 飞书 HTTP 200 仍可能表示业务失败，因此继续校验响应体 code。 */
    private fun requireSuccessCode(root: JsonNode, reasonCode: String) {
        val code = root.path("code")
        if (!code.canConvertToInt()) {
            throw remote(ErrorCode.FEISHU_RESPONSE_INVALID, "FEISHU_BUSINESS_CODE_MISSING")
        }
        if (code.asInt() != 0) {
            throw remote(
                ErrorCode.FEISHU_REQUEST_REJECTED,
                reasonCode,
                mapOf("upstreamCode" to code.asInt()),
            )
        }
    }

    /** 统一构造登录编排层可识别的远程依赖异常。 */
    private fun remote(
        errorCode: ErrorCode,
        reasonCode: String,
        details: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null,
    ): RemoteException = RemoteException(errorCode, reasonCode, details, cause)
}
