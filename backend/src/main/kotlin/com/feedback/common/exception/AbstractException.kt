package com.feedback.common.exception

import com.feedback.common.ErrorCode
import com.feedback.common.ErrorLayer

/**
 * 可安全映射为 API 错误响应的异常基类。
 *
 * `message` 固定来自 [ErrorCode]，既稳定前端合同，也避免把数据库、密钥或远程响应信息泄露给客户端。
 * 动态上下文通过 [reasonCode] 和 [details] 表达；调用方只能放入经过审查、允许公开的值。
 * 构造阶段同时校验错误码层级，避免错误的异常类型改变日志级别、监控维度和重试判断。
 */
sealed class AbstractException(
    val errorCode: ErrorCode,
    val layer: ErrorLayer,
    val reasonCode: String? = null,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null
) : RuntimeException(errorCode.message, cause) {

    val details: Map<String, Any?> = details.toMap()

    init {
        require(errorCode.layer == layer) {
            "ErrorCode ${errorCode.name} belongs to ${errorCode.layer}, not $layer"
        }
        require(reasonCode == null || reasonCode.matches(SAFE_REASON_CODE)) {
            "reasonCode must match ${SAFE_REASON_CODE.pattern}"
        }
    }

    private companion object {
        val SAFE_REASON_CODE = Regex("^[A-Z][A-Z0-9_]{2,80}$")
    }
}
