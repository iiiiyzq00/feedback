package com.feedback.service.report

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun reportInvalid(reasonCode:String):Nothing=throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
internal fun reportNotFound(reasonCode:String):Nothing=throw ClientException(ErrorCode.NOT_FOUND,reasonCode)
internal fun reportForbidden(reasonCode:String):Nothing=throw ClientException(ErrorCode.AUTH_FORBIDDEN,reasonCode)
internal fun reportUnauthorized(reasonCode:String):Nothing=throw ClientException(ErrorCode.AUTH_UNAUTHORIZED,reasonCode)
internal fun reportFailed(reasonCode:String,cause:Throwable?=null):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode,cause=cause)
