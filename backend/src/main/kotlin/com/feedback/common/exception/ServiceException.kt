package com.feedback.common.exception

import com.feedback.common.ErrorCode
import com.feedback.common.ErrorLayer

/**
 * 本服务内部业务状态、并发冲突或能力缺失导致的异常。
 * HTTP 状态可能是 4xx 或 5xx；分类依据是责任边界，而不是状态码数字。
 */
class ServiceException(
    errorCode: ErrorCode,
    reasonCode: String? = null,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null
) : AbstractException(errorCode, ErrorLayer.SERVICE, reasonCode, details, cause)
