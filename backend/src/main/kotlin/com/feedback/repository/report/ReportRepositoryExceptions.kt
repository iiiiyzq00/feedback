package com.feedback.repository.report

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun reportRepositoryNotFound(reasonCode:String):Nothing=throw ClientException(ErrorCode.NOT_FOUND,reasonCode)
internal fun reportRepositoryFailed(reasonCode:String):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
