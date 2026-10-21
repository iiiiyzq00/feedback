package com.feedback.service.ai

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun aiInvalid(reasonCode:String):Nothing=throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
internal fun aiNotFound(reasonCode:String):Nothing=throw ClientException(ErrorCode.NOT_FOUND,reasonCode)
internal fun aiFailed(reasonCode:String,cause:Throwable?=null):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode,cause=cause)
internal fun aiReason(message:Any?):String {
    val candidate=message?.toString()?.substringBefore(':')
    return candidate?.takeIf{it.matches(Regex("^[A-Z][A-Z0-9_]{2,80}$"))}?:"AI_VALIDATION_FAILED"
}
internal fun aiAbort(message:Any?):Nothing=aiInvalid(aiReason(message))
internal inline fun ensureAi(condition:Boolean,lazyMessage:()->Any={"AI_VALIDATION_FAILED"}) {
    if(!condition) aiInvalid(aiReason(lazyMessage()))
}
internal inline fun <T:Any> aiRequired(value:T?,lazyMessage:()->Any={"AI_REQUIRED_VALUE_MISSING"}):T =
    value?:aiInvalid(aiReason(lazyMessage()))
internal inline fun ensureAiState(condition:Boolean,lazyMessage:()->Any={"AI_STATE_INVALID"}) {
    if(!condition) aiFailed(aiReason(lazyMessage()))
}
internal inline fun <T:Any> aiStateRequired(value:T?,lazyMessage:()->Any={"AI_REQUIRED_STATE_MISSING"}):T =
    value?:aiFailed(aiReason(lazyMessage()))
