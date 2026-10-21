package com.feedback.config

import com.feedback.common.ApiResponse
import com.feedback.common.ApiErrorDetails
import com.feedback.common.ErrorCode
import com.feedback.common.exception.AbstractException
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * 将可预期异常转换为稳定的公开响应，并在此集中决定日志级别和监控维度。
 *
 * 只有 [AbstractException] 的错误码消息和已过滤详情可以返回客户端；未知异常统一隐藏为内部错误。
 */
@RestControllerAdvice
class GlobalExceptionHandler(private val meterRegistry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(ex: MethodArgumentNotValidException) = ResponseEntity.badRequest().body(
        ApiResponse.error(
            ErrorCode.BAD_REQUEST,
            ApiErrorDetails(fields = ex.bindingResult.fieldErrors.associate {
                it.field.take(MAX_FIELD_NAME_LENGTH) to (it.defaultMessage ?: "校验失败").take(MAX_FIELD_MESSAGE_LENGTH)
            })
        )
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Rejected unreadable request at {} {}", request.method, request.requestURI)
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(ex: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val reasonCode = safeReasonCode(ex.message)
        log.warn("Rejected request at {} {} reason={}", request.method, request.requestURI, reasonCode ?: "INVALID_ARGUMENT")
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, reasonCode?.let { ApiErrorDetails(reasonCode = it) }))
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missing(ex: MissingServletRequestParameterException) = ResponseEntity.badRequest().body(
        ApiResponse.error(ErrorCode.BAD_REQUEST, ApiErrorDetails(parameter = ex.parameterName.take(MAX_FIELD_NAME_LENGTH)))
    )

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(ex: AccessDeniedException) = ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ErrorCode.AUTH_FORBIDDEN))

    @ExceptionHandler(AuthenticationException::class)
    fun auth(ex: AuthenticationException) = ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(ErrorCode.AUTH_UNAUTHORIZED))

    @ExceptionHandler(ClientException::class)
    fun clientException(ex: ClientException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        log.warn(
            "Client exception at {} {} code={} reason={}",
            request.method,
            request.requestURI,
            ex.errorCode.name,
            safeReasonCode(ex.reasonCode) ?: "NONE"
        )
        recordException(ex)
        return exceptionResponse(ex)
    }

    @ExceptionHandler(ServiceException::class)
    fun serviceException(ex: ServiceException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        log.error(
            "Service exception at {} {} code={} reason={}",
            request.method,
            request.requestURI,
            ex.errorCode.name,
            safeReasonCode(ex.reasonCode) ?: "NONE",
            ex
        )
        recordException(ex)
        return exceptionResponse(ex)
    }

    @ExceptionHandler(RemoteException::class)
    fun remoteException(ex: RemoteException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        log.error(
            "Remote exception at {} {} code={} reason={}",
            request.method,
            request.requestURI,
            ex.errorCode.name,
            safeReasonCode(ex.reasonCode) ?: "NONE",
            ex
        )
        recordException(ex)
        return exceptionResponse(ex)
    }

    // 非反馈模块仍在迁移；反馈调用链由静态合同保证不再进入这些兼容入口。
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(ex: NoSuchElementException) = ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ErrorCode.NOT_FOUND))

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(ex: ResponseStatusException, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        val reasonCode = safeReasonCode(ex.reason)
        log.warn("Request unavailable at {} {} status={} reason={}", request.method, request.requestURI, ex.statusCode.value(), reasonCode ?: "HTTP_STATUS_ERROR")
        val errorCode = when (ex.statusCode.value()) {
            400 -> ErrorCode.BAD_REQUEST
            404 -> ErrorCode.NOT_FOUND
            else -> ErrorCode.INTERNAL_ERROR
        }
        return ResponseEntity.status(ex.statusCode).body(ApiResponse.error(errorCode, reasonCode?.let { ApiErrorDetails(reasonCode = it) }))
    }

    @ExceptionHandler(Exception::class)
    fun general(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unhandled exception at {} {} requestId={}", request.method, request.requestURI, request.getAttribute("requestId"), ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(ErrorCode.INTERNAL_ERROR))
    }

    private fun exceptionResponse(ex: AbstractException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(ex.errorCode.httpStatus).body(ApiResponse.error(ex.errorCode, responseDetails(ex)))

    private fun responseDetails(ex: AbstractException): ApiErrorDetails? {
        val reasonCode = safeReasonCode(ex.reasonCode ?: ex.details["reasonCode"] as? String)
        val fields = (ex.details["fields"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) ->
                val safeKey = (key as? String)?.takeIf(SAFE_FIELD_NAME::matches)?.take(MAX_FIELD_NAME_LENGTH)
                    ?: return@mapNotNull null
                val safeValue = (value as? String)?.take(MAX_FIELD_MESSAGE_LENGTH)
                    ?: return@mapNotNull null
                safeKey to safeValue
            }
            ?.toMap()
            ?.takeIf(Map<String, String>::isNotEmpty)
        val parameter = (ex.details["parameter"] as? String)
            ?.takeIf(SAFE_FIELD_NAME::matches)
            ?.take(MAX_FIELD_NAME_LENGTH)
        val upstreamStatus = (ex.details["upstreamStatus"] as? Number)?.toInt()?.takeIf { it in 100..599 }
        val retryAfterSeconds = (ex.details["retryAfterSeconds"] as? Number)?.toLong()?.takeIf { it in 1..3600 }
        return ApiErrorDetails(reasonCode, fields, parameter, upstreamStatus, retryAfterSeconds)
            .takeIf { it.reasonCode != null || it.fields != null || it.parameter != null || it.upstreamStatus != null || it.retryAfterSeconds != null }
    }

    private fun recordException(ex: AbstractException) {
        meterRegistry.counter(
            "feedback.exceptions",
            "layer", ex.layer.metricTag,
            "code", ex.errorCode.name,
            "http_status", ex.errorCode.httpStatus.value().toString()
        ).increment()
    }

    private fun safeReasonCode(value: String?): String? =
        value?.takeIf { it.matches(SAFE_REASON_CODE) }

    private companion object {
        val SAFE_REASON_CODE = Regex("^[A-Z][A-Z0-9_]{2,80}$")
        val SAFE_FIELD_NAME = Regex("^[A-Za-z][A-Za-z0-9_.]{0,99}$")
        const val MAX_FIELD_NAME_LENGTH = 100
        const val MAX_FIELD_MESSAGE_LENGTH = 200
    }
}
