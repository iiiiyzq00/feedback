package com.feedback.repository.feedback.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun invalidProjectionInput(reasonCode: String): Nothing =
    throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

internal fun projectionBuildFailed(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED, reasonCode)

internal fun projectionReadUnavailable(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, reasonCode)
