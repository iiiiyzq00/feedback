package com.feedback.common.exception

import com.feedback.common.ErrorCode
import com.feedback.common.ErrorLayer

/**
 * 已越过进程边界的依赖调用失败、被限流或超时时抛出的异常。
 * 独立类型用于区分本服务故障与依赖方故障，并驱动监控和重试策略。
 */
class RemoteException(
    errorCode: ErrorCode,
    reasonCode: String? = null,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null
) : AbstractException(errorCode, ErrorLayer.REMOTE, reasonCode, details, cause)
