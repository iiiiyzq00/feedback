package com.feedback.service.jira

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import com.feedback.config.Jira400Properties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
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

data class JiraApiIssue(val id: String, val key: String, val fields: JsonNode)
data class JiraApiPage(val issues: List<JiraApiIssue>, val startAt: Int, val total: Int, val isLast: Boolean)

/** Jira 只读查询端口；调用方只处理领域分页，不依赖 HTTP、认证头或响应 JSON 结构。 */
interface Jira400Gateway {
    fun search(jql: String, startAt: Int, maxResults: Int, fields: List<String>): JiraApiPage
    fun project(key: String): JsonNode? = null
}

@Component
class Jira400ApiClient(
    private val properties: Jira400Properties,
    private val mapper: ObjectMapper
) : Jira400Gateway {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
            .readTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
            .callTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
            .build()
    }

    override fun search(jql: String, startAt: Int, maxResults: Int, fields: List<String>): JiraApiPage {
        if(!properties.ready()) throw ServiceException(ErrorCode.INTERNAL_ERROR,"JIRA_CONFIGURATION_NOT_READY")
        val base = properties.baseUrl.removeSuffix("/")
        val requestBuilder = if (properties.httpMethod.uppercase() == "GET") {
            val url = "$base${properties.searchPath}".toHttpUrl().newBuilder()
                .addQueryParameter("jql", jql)
                .addQueryParameter("startAt", startAt.toString())
                .addQueryParameter("maxResults", maxResults.toString())
                .addQueryParameter("fields", fields.joinToString(","))
                .build()
            Request.Builder().url(url).get()
        } else {
            val body = mapper.writeValueAsBytes(mapOf(
                "jql" to jql,
                "startAt" to startAt,
                "maxResults" to maxResults,
                "fields" to fields
            ))
            Request.Builder().url("$base${properties.searchPath}").post(body.toRequestBody(jsonMediaType))
        }
        requestBuilder.header(properties.authHeader, authorizationValue())
            .header("Accept", "application/json")
            .header("User-Agent", "feedback-platform-jira400-readonly/1")
        return execute(requestBuilder.build(), "JIRA_SEARCH_IO_FAILED") { response ->
            val bytes = response.body?.bytes() ?: throw remote(
                ErrorCode.JIRA_RESPONSE_INVALID, "JIRA_EMPTY_RESPONSE"
            )
            try {
                val root = try {
                    mapper.readTree(bytes)
                } catch (_: JacksonException) {
                    // Jackson 的原始消息可能包含响应片段，不能挂到可被全局日志记录的 cause。
                    throw remote(ErrorCode.JIRA_RESPONSE_INVALID, "JIRA_RESPONSE_MALFORMED")
                }
                val issuesNode = root.path("issues")
                if (!issuesNode.isArray) throw remote(ErrorCode.JIRA_RESPONSE_INVALID, "JIRA_ISSUES_MISSING")
                val pageStart = root.path("startAt").takeIf(JsonNode::isInt)?.asInt() ?: startAt
                val total = root.path("total").takeIf(JsonNode::isInt)?.asInt() ?: (pageStart + issuesNode.size())
                val issues = issuesNode.map { node ->
                    val id = node.path("id").asText().takeIf(String::isNotBlank)
                        ?: throw remote(ErrorCode.JIRA_RESPONSE_INVALID, "JIRA_ISSUE_ID_MISSING")
                    val key = node.path("key").asText().takeIf(String::isNotBlank)
                        ?: throw remote(ErrorCode.JIRA_RESPONSE_INVALID, "JIRA_ISSUE_KEY_MISSING")
                    val issueFields = node.path("fields")
                    if (!issueFields.isObject) throw remote(ErrorCode.JIRA_RESPONSE_INVALID, "JIRA_ISSUE_FIELDS_MISSING")
                    JiraApiIssue(id, key, issueFields)
                }
                val explicitLast = root.path("isLast").takeIf(JsonNode::isBoolean)?.asBoolean()
                JiraApiPage(issues, pageStart, total, explicitLast ?: (pageStart + issues.size >= total || issues.isEmpty()))
            } finally {
                bytes.fill(0)
            }
        } ?: throw ServiceException(ErrorCode.INTERNAL_ERROR,"JIRA_SEARCH_RESULT_MISSING")
    }

    override fun project(key: String): JsonNode? {
        if(!key.matches(Regex("^[A-Za-z][A-Za-z0-9_]{0,19}$")))
            throw ClientException(ErrorCode.BAD_REQUEST,"JIRA_PROJECT_KEY_INVALID")
        val request = Request.Builder()
            .url("${properties.baseUrl.removeSuffix("/")}/rest/api/2/project/$key")
            .header(properties.authHeader, authorizationValue())
            .header("Accept", "application/json")
            .header("User-Agent", "feedback-platform-jira400-readonly/1")
            .get().build()
        return execute(request, "JIRA_PROJECT_IO_FAILED", notFoundIsNull = true) { response ->
            val bytes = response.body?.bytes() ?: throw remote(
                ErrorCode.JIRA_RESPONSE_INVALID, "JIRA_PROJECT_EMPTY_RESPONSE"
            )
            try {
                try {
                    mapper.readTree(bytes)
                } catch (_: JacksonException) {
                    throw remote(ErrorCode.JIRA_RESPONSE_INVALID, "JIRA_PROJECT_RESPONSE_MALFORMED")
                }
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun <T> execute(
        request: Request,
        ioReason: String,
        notFoundIsNull: Boolean = false,
        body: (okhttp3.Response) -> T
    ): T? {
        try {
            client.newCall(request).execute().use { response ->
                if (notFoundIsNull && response.code == 404) return null
                when (response.code) {
                    401 -> throw remote(ErrorCode.JIRA_AUTHENTICATION_FAILED, "JIRA_AUTHENTICATION_FAILED")
                    403 -> throw remote(ErrorCode.JIRA_ACCESS_FORBIDDEN, "JIRA_ACCESS_FORBIDDEN")
                    408, 504 -> throw remote(ErrorCode.REMOTE_TIMEOUT, "JIRA_REMOTE_TIMEOUT")
                    429 -> throw remote(
                        ErrorCode.JIRA_RATE_LIMITED,
                        "JIRA_RATE_LIMITED",
                        mapOf("retryAfterSeconds" to parseRetryAfterSeconds(response.header("Retry-After")))
                    )
                    in 400..499 -> throw remote(
                        ErrorCode.JIRA_REQUEST_REJECTED,
                        "JIRA_HTTP_${response.code}",
                        mapOf("upstreamStatus" to response.code)
                    )
                    in 500..599 -> throw remote(
                        ErrorCode.JIRA_UNAVAILABLE,
                        "JIRA_HTTP_${response.code}",
                        mapOf("upstreamStatus" to response.code)
                    )
                }
                if (!response.isSuccessful) throw remote(
                    ErrorCode.JIRA_UNAVAILABLE,
                    "JIRA_HTTP_UNEXPECTED",
                    mapOf("upstreamStatus" to response.code)
                )
                return body(response)
            }
        } catch (error: RemoteException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw remote(ErrorCode.REMOTE_TIMEOUT, "JIRA_REMOTE_TIMEOUT", cause = error)
        } catch (error: IOException) {
            throw remote(ErrorCode.JIRA_UNAVAILABLE, ioReason, cause = error)
        }
    }

    private fun parseRetryAfterSeconds(value: String?): Long =
        value?.trim()?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L

    private fun remote(
        errorCode: ErrorCode,
        reasonCode: String,
        details: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null
    ) = RemoteException(errorCode, reasonCode, details.filterValues { it != null }, cause)

    private fun authorizationValue(): String = when (properties.authType.uppercase()) {
        "BEARER" -> "Bearer ${properties.token}"
        "BASIC" -> "Basic ${properties.token}"
        else -> properties.tokenPrefix + properties.token
    }
}
