package com.feedback.repository.remote.auth

import com.feedback.model.auth.FeishuOrganizationFacts

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.config.FeishuSsoProperties
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * 飞书官方组织通讯录 HTTP 客户端。
 * 每次调用按固定超时顺序读取 token、用户和用户组；令牌不落库、不记录日志，失败统一转换为远程异常。
 */
@Component
class OfficialFeishuOrganizationClient(
    private val properties: FeishuSsoProperties,
    private val mapper: ObjectMapper
) : FeishuOrganizationClient {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
            .readTimeout(Duration.ofSeconds(properties.readTimeoutSeconds))
            .callTimeout(Duration.ofSeconds(properties.callTimeoutSeconds))
            .build()
    }
    private val json = "application/json; charset=utf-8".toMediaType()

    /** 拉取授权计算所需的最小组织事实；本方法包含外部 I/O，禁止在数据库事务中调用。 */
    override fun facts(openId: String): FeishuOrganizationFacts {
        properties.validateOrganizationEnabled()
        if (!openId.matches(Regex("^[A-Za-z0-9_-]{1,128}$"))) {
            throw ClientException(ErrorCode.BAD_REQUEST, "FEISHU_OPEN_ID_INVALID")
        }

        val tokenBytes = mapper.writeValueAsBytes(
            mapOf("app_id" to properties.appId, "app_secret" to properties.appSecret)
        )
        val tokenRoot = try {
            execute(
                Request.Builder()
                    .url(url("open-apis", "auth", "v3", "tenant_access_token", "internal"))
                    .post(tokenBytes.toRequestBody(json))
                    .header("Accept", "application/json")
                    .build(),
                "FEISHU_TENANT_TOKEN_IO_FAILED"
            )
        } finally {
            tokenBytes.fill(0)
        }
        requireSuccessCode(tokenRoot, "FEISHU_TENANT_TOKEN_REJECTED")
        val token = tokenRoot.path("tenant_access_token").asText().takeIf(String::isNotBlank)
            ?: throw remote(ErrorCode.FEISHU_RESPONSE_INVALID, "FEISHU_TENANT_TOKEN_MISSING")

        val userRoot = execute(
            Request.Builder()
                .url(
                    url("open-apis", "contact", "v3", "users", openId).newBuilder()
                        .addQueryParameter("user_id_type", "open_id")
                        .addQueryParameter("department_id_type", "open_department_id")
                        .build()
                )
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build(),
            "FEISHU_USER_ORGANIZATION_IO_FAILED"
        )
        requireSuccessCode(userRoot, "FEISHU_USER_ORGANIZATION_REJECTED")

        val groupsRoot = execute(
            Request.Builder()
                .url(
                    url("open-apis", "contact", "v3", "group", "member_belong").newBuilder()
                        .addQueryParameter("member_id", openId)
                        .addQueryParameter("member_id_type", "open_id")
                        .build()
                )
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build(),
            "FEISHU_GROUP_MEMBERSHIP_IO_FAILED"
        )
        requireSuccessCode(groupsRoot, "FEISHU_GROUP_MEMBERSHIP_REJECTED")

        val user = userRoot.path("data").path("user")
        val groups = groupsRoot.path("data").path("group_list")
        if (!user.isObject || !groups.isArray || !user.path("department_ids").isArray || !user.path("status").isObject) {
            throw remote(ErrorCode.FEISHU_RESPONSE_INVALID, "FEISHU_ORGANIZATION_DATA_MISSING")
        }
        return FeishuOrganizationFacts(
            groupIds = groups.mapNotNull { it.path("group_id").asText().takeIf(String::isNotBlank) }.toSet(),
            departmentIds = user.path("department_ids").mapNotNull { it.asText().takeIf(String::isNotBlank) }.toSet(),
            active = user.path("status").path("is_activated").asBoolean(true) &&
                !user.path("status").path("is_resigned").asBoolean(false)
        )
    }

    /** 逐段构造飞书 API URL，避免调用方直接拼接未编码路径。 */
    private fun url(vararg segments: String) = properties.organizationApiBaseUrl.trimEnd('/').toHttpUrl()
        .newBuilder().apply { segments.forEach(::addPathSegment) }.build()

    /** 执行单次 HTTP 请求并清除响应字节；超时、I/O 和非法 JSON 转换为稳定远程错误。 */
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

    /** 将飞书 HTTP 状态映射为平台重试语义，并限制 Retry-After 范围。 */
    private fun mapStatus(status: Int, retryAfter: String?) {
        when (status) {
            in 200..299 -> return
            401, 403 -> throw remote(ErrorCode.FEISHU_AUTHENTICATION_FAILED, "FEISHU_HTTP_$status")
            408, 504 -> throw remote(ErrorCode.REMOTE_TIMEOUT, "FEISHU_REMOTE_TIMEOUT")
            429 -> throw remote(
                ErrorCode.FEISHU_RATE_LIMITED,
                "FEISHU_RATE_LIMITED",
                mapOf("retryAfterSeconds" to (retryAfter?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L))
            )
            in 400..499 -> throw remote(
                ErrorCode.FEISHU_REQUEST_REJECTED,
                "FEISHU_HTTP_$status",
                mapOf("upstreamStatus" to status)
            )
            else -> throw remote(
                ErrorCode.FEISHU_UNAVAILABLE,
                "FEISHU_HTTP_$status",
                mapOf("upstreamStatus" to status)
            )
        }
    }

    /** 飞书 HTTP 200 仍需校验业务 code；缺失或非零均失败关闭。 */
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

    /** 统一构造不泄露令牌和响应原文的远程依赖异常。 */
    private fun remote(
        errorCode: ErrorCode,
        reasonCode: String,
        details: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null
    ) = RemoteException(errorCode, reasonCode, details, cause)
}
