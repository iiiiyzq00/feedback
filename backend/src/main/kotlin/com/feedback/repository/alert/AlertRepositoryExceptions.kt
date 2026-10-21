package com.feedback.repository.alert

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun alertRepositoryReason(message:Any?):String=message?.toString()?.substringBefore(':')
    ?.takeIf{it.matches(Regex("^[A-Z][A-Z0-9_]{2,80}$"))}?:"ALERT_REPOSITORY_STATE_INVALID"
internal fun alertRepositoryFailed(reasonCode:String):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
internal fun alertRepositoryMissing(message:Any?):Nothing=throw ClientException(ErrorCode.NOT_FOUND,alertRepositoryReason(message))
internal fun alertRepositoryAbort(message:Any?):Nothing=alertRepositoryFailed(alertRepositoryReason(message))
internal inline fun ensureAlertRepositoryInput(condition:Boolean,lazyMessage:()->Any={"ALERT_REPOSITORY_INPUT_INVALID"}) {
    if(!condition) throw ClientException(ErrorCode.BAD_REQUEST,alertRepositoryReason(lazyMessage()))
}
internal inline fun <T:Any> alertRepositoryRequired(value:T?,lazyMessage:()->Any={"ALERT_REQUIRED_VALUE_MISSING"}):T =
    value?:throw ClientException(ErrorCode.BAD_REQUEST,alertRepositoryReason(lazyMessage()))
internal inline fun ensureAlertRepositoryState(condition:Boolean,lazyMessage:()->Any={"ALERT_REPOSITORY_STATE_INVALID"}) {
    if(!condition) alertRepositoryFailed(alertRepositoryReason(lazyMessage()))
}
internal inline fun <T:Any> alertRepositoryStateRequired(value:T?,lazyMessage:()->Any={"ALERT_REPOSITORY_STATE_MISSING"}):T =
    value?:alertRepositoryFailed(alertRepositoryReason(lazyMessage()))
