package com.feedback.service.alert

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

internal fun alertInvalid(reasonCode:String):Nothing=throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
internal fun alertNotFound(reasonCode:String):Nothing=throw ClientException(ErrorCode.NOT_FOUND,reasonCode)
internal fun alertForbidden(reasonCode:String):Nothing=throw ClientException(ErrorCode.AUTH_FORBIDDEN,reasonCode)
internal fun alertUnauthorized(reasonCode:String):Nothing=throw ClientException(ErrorCode.AUTH_UNAUTHORIZED,reasonCode)
internal fun alertFailed(reasonCode:String,cause:Throwable?=null):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode,cause=cause)
internal fun alertReason(message:Any?):String=message?.toString()?.substringBefore(':')
    ?.takeIf{it.matches(Regex("^[A-Z][A-Z0-9_]{2,80}$"))}?:"ALERT_VALIDATION_FAILED"
internal fun alertAbort(message:Any?):Nothing=alertInvalid(alertReason(message))
internal fun alertMissing(message:Any?):Nothing=alertNotFound(alertReason(message))
internal fun alertDeny(message:Any?):Nothing=alertForbidden(alertReason(message))
internal fun alertStateAbort(message:Any?):Nothing=alertFailed(alertReason(message))
internal inline fun ensureAlert(condition:Boolean,lazyMessage:()->Any={"ALERT_VALIDATION_FAILED"}) {
    if(!condition) alertInvalid(alertReason(lazyMessage()))
}
internal inline fun <T:Any> alertRequired(value:T?,lazyMessage:()->Any={"ALERT_REQUIRED_VALUE_MISSING"}):T =
    value?:alertInvalid(alertReason(lazyMessage()))
internal inline fun ensureAlertState(condition:Boolean,lazyMessage:()->Any={"ALERT_STATE_INVALID"}) {
    if(!condition) alertFailed(alertReason(lazyMessage()))
}
internal inline fun <T:Any> alertStateRequired(value:T?,lazyMessage:()->Any={"ALERT_REQUIRED_STATE_MISSING"}):T =
    value?:alertFailed(alertReason(lazyMessage()))
