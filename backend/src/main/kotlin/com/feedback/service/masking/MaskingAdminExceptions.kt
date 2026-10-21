package com.feedback.service.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun maskingInvalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
internal fun maskingNotFound(reasonCode:String):Nothing = throw ClientException(ErrorCode.NOT_FOUND,reasonCode)
internal fun maskingForbidden(reasonCode:String):Nothing = throw ClientException(ErrorCode.AUTH_FORBIDDEN,reasonCode)
internal fun maskingUnauthorized(reasonCode:String):Nothing = throw ClientException(ErrorCode.AUTH_UNAUTHORIZED,reasonCode)
internal fun maskingFailed(reasonCode:String):Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
internal fun maskingDecryptFailed(reasonCode:String):Nothing = throw ServiceException(ErrorCode.MASKING_DECRYPT_FAILED,reasonCode)
