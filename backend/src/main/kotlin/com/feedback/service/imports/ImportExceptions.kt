package com.feedback.service.imports

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun importInvalid(reasonCode:String):Nothing=throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
internal fun importNotFound(reasonCode:String):Nothing=throw ClientException(ErrorCode.IMPORT_BATCH_NOT_FOUND,reasonCode)
internal fun importForbidden(reasonCode:String):Nothing=throw ClientException(ErrorCode.AUTH_FORBIDDEN,reasonCode)
internal fun importUnauthorized(reasonCode:String):Nothing=throw ClientException(ErrorCode.AUTH_UNAUTHORIZED,reasonCode)
internal fun importFailed(reasonCode:String,cause:Throwable?=null):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode,cause=cause)

internal fun importReason(message:Any?):String {
    val candidate=message?.toString()?.substringBefore(':')
    return candidate?.takeIf{it.matches(Regex("^[A-Z][A-Z0-9_]{2,80}$"))}?:"IMPORT_VALIDATION_FAILED"
}
internal fun importAbort(message:Any?):Nothing=importInvalid(importReason(message))
internal inline fun ensureImport(condition:Boolean,lazyMessage:()->Any={"IMPORT_VALIDATION_FAILED"}) {
    if(!condition) importInvalid(importReason(lazyMessage()))
}
internal inline fun <T:Any> importRequired(value:T?,lazyMessage:()->Any={"IMPORT_REQUIRED_VALUE_MISSING"}):T =
    value?:importInvalid(importReason(lazyMessage()))
internal inline fun ensureImportState(condition:Boolean,lazyMessage:()->Any={"IMPORT_STATE_INVALID"}) {
    if(!condition) importFailed(importReason(lazyMessage()))
}
internal inline fun <T:Any> importStateRequired(value:T?,lazyMessage:()->Any={"IMPORT_REQUIRED_STATE_MISSING"}):T =
    value?:importFailed(importReason(lazyMessage()))
