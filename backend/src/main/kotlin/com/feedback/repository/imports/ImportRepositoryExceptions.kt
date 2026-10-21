package com.feedback.repository.imports

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun importRepositoryNotFound(reasonCode:String):Nothing=throw ClientException(ErrorCode.IMPORT_BATCH_NOT_FOUND,reasonCode)
internal fun importRepositoryFailed(reasonCode:String):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
internal fun importRepositoryReason(message:Any?):String=message?.toString()?.substringBefore(':')
    ?.takeIf{it.matches(Regex("^[A-Z][A-Z0-9_]{2,80}$"))}?:"IMPORT_REPOSITORY_STATE_INVALID"
internal inline fun ensureImportRepositoryInput(condition:Boolean,lazyMessage:()->Any={"IMPORT_REPOSITORY_INPUT_INVALID"}) {
    if(!condition) throw ClientException(ErrorCode.BAD_REQUEST,importRepositoryReason(lazyMessage()))
}
internal inline fun <T:Any> importRepositoryRequired(value:T?,lazyMessage:()->Any={"IMPORT_REQUIRED_VALUE_MISSING"}):T =
    value?:throw ClientException(ErrorCode.BAD_REQUEST,importRepositoryReason(lazyMessage()))
internal inline fun ensureImportRepositoryState(condition:Boolean,lazyMessage:()->Any={"IMPORT_REPOSITORY_STATE_INVALID"}) {
    if(!condition) importRepositoryFailed(importRepositoryReason(lazyMessage()))
}
internal inline fun <T:Any> importRepositoryStateRequired(value:T?,lazyMessage:()->Any={"IMPORT_REPOSITORY_STATE_MISSING"}):T =
    value?:importRepositoryFailed(importRepositoryReason(lazyMessage()))
