package com.feedback.common

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.MDC
import java.time.Instant

/**
 * 统一 API 响应封装。
 *
 * 成功响应协议独立于 [ErrorCode]；[SUCCESS_CODE] 的值 200 是现有前端合同，不能随意调整。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T? = null,
    val requestId: String? = MDC.get("requestId"),
    val retryable: Boolean? = null,
    val details: ApiErrorDetails? = null,
    val timestamp: Instant = Instant.now()
) {
    companion object {
        const val SUCCESS_CODE = 200
        const val SUCCESS_MESSAGE = "success"

        fun <T> success(data: T): ApiResponse<T> =
            ApiResponse(SUCCESS_CODE, SUCCESS_MESSAGE, data)

        fun <T> success(data: T, message: String): ApiResponse<T> =
            ApiResponse(SUCCESS_CODE, message, data)

        fun error(errorCode: ErrorCode, details: ApiErrorDetails? = null): ApiResponse<Nothing> =
            ApiResponse(errorCode.code, errorCode.message, null, retryable = errorCode.retryable, details = details)

        fun <T> of(code: Int, message: String, data: T? = null): ApiResponse<T> =
            ApiResponse(code, message, data)
    }
}

/**
 * 对外错误详情白名单。
 *
 * 禁止用开放 Map 直接扩展公开错误合同；新增字段必须在这里声明类型、边界和脱敏规则。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiErrorDetails(
    val reasonCode: String? = null,
    val fields: Map<String, String>? = null,
    val parameter: String? = null,
    val upstreamStatus: Int? = null,
    val retryAfterSeconds: Long? = null
)

/** 分页响应。 */
data class PageResponse<T>(
    val records: List<T>,
    val total: Long,
    val page: Int,
    val size: Int
)
