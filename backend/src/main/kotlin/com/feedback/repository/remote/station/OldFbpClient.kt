package com.feedback.repository.remote.station

import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import com.feedback.config.StationDualWriteProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

/** 旧反馈平台 HTTP 客户端；网络调用位于事务外并统一归一远端错误。 */
@Component
class OldFbpClient(
    private val properties: StationDualWriteProperties,
    private val mapper: ObjectMapper
) : OldFbpGateway {
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedUntil: Long = 0
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
            .callTimeout(Duration.ofSeconds(properties.callTimeoutSeconds))
            .build()
    }

    /** 提交 Station RPC/旧平台请求；保持外部协议兼容，并把远端失败归一为安全错误码。 */
    override fun submit(envelope: StationDualWriteEnvelope) {
        if (!properties.ready()) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "OLD_FBP_CONFIGURATION_INVALID")
        }
        try {
            submitOnce(envelope, accessToken())
        } catch (error: RemoteException) {
            if (error.reasonCode != "OLD_FBP_SUBMIT_UNAUTHORIZED") {
                throw error
            }
            // 仅认证失效允许刷新令牌后重放一次；其他错误由任务层按 retryable 语义调度。
            invalidateToken()
            submitOnce(envelope, accessToken())
        }
    }

    /** 使用当前访问令牌向旧平台提交一次请求；响应体有界解析，失败统一转换为远端异常。 */
    private fun submitOnce(envelope: StationDualWriteEnvelope, token: String) {
        val feedback = envelope.request
        val body = linkedMapOf<String, Any>(
            "platform" to feedback.platform, "feedbackPerson" to envelope.principal.userId,
            "type" to feedback.type, "channel" to feedback.channel, "title" to feedback.title,
            "content" to feedback.content, "version" to feedback.appVersion, "productId" to feedback.productId,
            "feedbackPersonPhone" to feedback.phone, "feedbackAttributeRecords" to emptyList<Any>(),
            "description" to feedback.description, "appId" to feedback.appId, "appName" to feedback.appName
        )
        if (!feedback.photoUrlList.isNullOrEmpty()) {
            body["picture"] = mapper.writeValueAsString(feedback.photoUrlList.map { mapOf("name" to "feedback.jpg", "url" to it) })
        }
        val url = properties.oldFbpSubmitBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/product").addPathSegment(feedback.productId).addPathSegment("feedback").build()
        val request = Request.Builder().url(url).header("access-token", token)
            .post(mapper.writeValueAsBytes(body).toRequestBody(mediaType)).build()
        execute(request, "OLD_FBP_SUBMIT_IO_FAILED") { response ->
            val bytes = response.body?.bytes() ?: throw remote(
                ErrorCode.OLD_FBP_RESPONSE_INVALID, "OLD_FBP_EMPTY_RESPONSE"
            )
            try {
                val root = try {
                    mapper.readTree(bytes)
                } catch (_: JacksonException) {
                    throw remote(ErrorCode.OLD_FBP_RESPONSE_INVALID, "OLD_FBP_RESPONSE_MALFORMED")
                }
                if (root.path("status").asText() != "0") {
                    throw remote(
                        ErrorCode.OLD_FBP_REQUEST_REJECTED,
                        "OLD_FBP_REJECTED"
                    )
                }
            } finally {
                bytes.fill(0)
            }
        }
    }

    /** 获取并短期缓存旧平台访问令牌；远端网络调用不允许位于数据库事务中。 */
    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < cachedUntil }?.let { return it }
        return synchronized(this) {
            val checked = System.currentTimeMillis()
            cachedToken?.takeIf { checked < cachedUntil }?.let { return@synchronized it }
            val url = properties.oldFbpTokenUrl.toHttpUrl().newBuilder()
                .addQueryParameter("appid", properties.oldFbpAppId)
                .addQueryParameter("secret", properties.oldFbpSecret).build()
            val request = Request.Builder().url(url).get().build()
            val token = execute(request, "OLD_FBP_TOKEN_IO_FAILED") { response ->
                val bytes = response.body?.bytes() ?: throw remote(
                    ErrorCode.OLD_FBP_RESPONSE_INVALID, "OLD_FBP_TOKEN_EMPTY"
                )
                try {
                    val root = try {
                        mapper.readTree(bytes)
                    } catch (_: JacksonException) {
                        throw remote(ErrorCode.OLD_FBP_RESPONSE_INVALID, "OLD_FBP_TOKEN_RESPONSE_MALFORMED")
                    }
                    if (root.path("status").asInt() != 200) {
                        throw remote(
                            ErrorCode.OLD_FBP_AUTHENTICATION_FAILED,
                            "OLD_FBP_TOKEN_REJECTED"
                        )
                    }
                    root.path("data").path("accessToken").asText().takeIf(String::isNotBlank)
                        ?: throw remote(ErrorCode.OLD_FBP_RESPONSE_INVALID, "OLD_FBP_TOKEN_MISSING")
                } finally {
                    bytes.fill(0)
                }
            }
            cachedToken = token
            cachedUntil = checked + properties.tokenCacheSeconds * 1000
            token
        }
    }

    /** 执行一次旧平台 HTTP 调用并分类状态码；网络 I/O 位于事务外，响应字节及时清零。 */
    private fun <T> execute(request: Request, ioReason: String, body: (okhttp3.Response) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw remote(
                        ErrorCode.OLD_FBP_AUTHENTICATION_FAILED,
                        if (ioReason.startsWith("OLD_FBP_SUBMIT")) "OLD_FBP_SUBMIT_UNAUTHORIZED" else "OLD_FBP_TOKEN_UNAUTHORIZED"
                    )
                    408, 504 -> throw remote(ErrorCode.REMOTE_TIMEOUT, "OLD_FBP_REMOTE_TIMEOUT")
                    in 400..499 -> throw remote(
                        ErrorCode.OLD_FBP_REQUEST_REJECTED,
                        "OLD_FBP_HTTP_${response.code}",
                        mapOf("upstreamStatus" to response.code)
                    )
                    in 500..599 -> throw remote(
                        ErrorCode.OLD_FBP_UNAVAILABLE,
                        "OLD_FBP_HTTP_${response.code}",
                        mapOf("upstreamStatus" to response.code)
                    )
                }
                if (!response.isSuccessful) {
                    throw remote(
                        ErrorCode.OLD_FBP_UNAVAILABLE,
                        "OLD_FBP_HTTP_UNEXPECTED",
                        mapOf("upstreamStatus" to response.code)
                    )
                }
                return body(response)
            }
        } catch (error: RemoteException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw remote(ErrorCode.REMOTE_TIMEOUT, "OLD_FBP_REMOTE_TIMEOUT", cause = error)
        } catch (error: IOException) {
            throw remote(ErrorCode.OLD_FBP_UNAVAILABLE, ioReason, cause = error)
        }
    }

    /** 使旧平台令牌缓存立即失效，用于授权失败后的单次安全重试。 */
    private fun invalidateToken() = synchronized(this) {
        cachedToken = null
        cachedUntil = 0
    }

    /** 构造旧平台远端异常，只携带稳定原因码和允许的上游状态信息。 */
    private fun remote(
        errorCode: ErrorCode,
        reasonCode: String,
        details: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null
    ) = RemoteException(errorCode, reasonCode, details, cause)
}
