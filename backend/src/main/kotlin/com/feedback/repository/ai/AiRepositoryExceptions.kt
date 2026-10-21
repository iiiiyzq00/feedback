package com.feedback.repository.ai

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun aiRepositoryReason(message:Any?):String=message?.toString()?.substringBefore(':')
    ?.takeIf{it.matches(Regex("^[A-Z][A-Z0-9_]{2,80}$"))}?:"AI_REPOSITORY_STATE_INVALID"
internal fun aiRepositoryFailed(reasonCode:String):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
internal inline fun ensureAiRepositoryInput(condition:Boolean,lazyMessage:()->Any={"AI_REPOSITORY_INPUT_INVALID"}) {
    if(!condition) throw ClientException(ErrorCode.BAD_REQUEST,aiRepositoryReason(lazyMessage()))
}
internal inline fun <T:Any> aiRepositoryRequired(value:T?,lazyMessage:()->Any={"AI_REQUIRED_VALUE_MISSING"}):T =
    value?:throw ClientException(ErrorCode.BAD_REQUEST,aiRepositoryReason(lazyMessage()))
internal inline fun ensureAiRepositoryState(condition:Boolean,lazyMessage:()->Any={"AI_REPOSITORY_STATE_INVALID"}) {
    if(!condition) aiRepositoryFailed(aiRepositoryReason(lazyMessage()))
}
internal inline fun <T:Any> aiRepositoryStateRequired(value:T?,lazyMessage:()->Any={"AI_REPOSITORY_STATE_MISSING"}):T =
    value?:aiRepositoryFailed(aiRepositoryReason(lazyMessage()))
