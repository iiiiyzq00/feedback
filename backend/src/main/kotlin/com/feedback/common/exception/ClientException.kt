package com.feedback.common.exception

import com.feedback.common.ErrorCode
import com.feedback.common.ErrorLayer

/**
 * 客户端可通过修正输入、权限或请求时机解决的异常。
 * 该类型是全局处理器记录 WARN 而非服务故障告警的依据，不等同于“所有 4xx”。
 */
class ClientException(
    errorCode: ErrorCode,
    reasonCode: String? = null,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null
) : AbstractException(errorCode, ErrorLayer.CLIENT, reasonCode, details, cause)
