package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun invalidProjectionCommand(reasonCode: String): Nothing =
    throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

internal fun projectionOperationFailed(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED, reasonCode)

internal fun projectionCatchUpRequired(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED, reasonCode)
