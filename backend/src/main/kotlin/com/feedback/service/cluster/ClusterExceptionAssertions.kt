package com.feedback.service.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal inline fun ensureClusterRequest(
    condition: Boolean,
    errorCode: ErrorCode = ErrorCode.BAD_REQUEST,
    reasonCode: () -> String
) {
    if (!condition) throw ClientException(errorCode, reasonCode())
}

internal inline fun ensureClusterInvariant(
    condition: Boolean,
    errorCode: ErrorCode = ErrorCode.INTERNAL_ERROR,
    reasonCode: () -> String
) {
    if (!condition) throw ServiceException(errorCode, reasonCode())
}

internal fun clusterInvariantViolation(
    reasonCode: String,
    errorCode: ErrorCode = ErrorCode.INTERNAL_ERROR
): Nothing = throw ServiceException(errorCode, reasonCode)
