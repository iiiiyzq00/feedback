package com.feedback.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ApiResponse
import com.feedback.common.ErrorCode
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component

/**
 * 为 Spring Security 过滤器链写入统一错误协议。
 * 过滤器异常不会进入 MVC 的全局异常处理器，因此必须在此复用 [ErrorCode] 与 [ApiResponse] 合同。
 */
@Component
class SecurityErrorWriter(private val mapper: ObjectMapper) {
    /** 会话缺失或失效时返回统一 401 协议。 */
    fun unauthorized(response: HttpServletResponse) = write(response, ErrorCode.AUTH_UNAUTHORIZED)

    /** 已识别身份但不满足授权条件时返回统一 403 协议。 */
    fun forbidden(response: HttpServletResponse) = write(response, ErrorCode.AUTH_FORBIDDEN)

    /** 在过滤器层直接写入状态码和 JSON，避免响应继续进入 MVC。 */
    private fun write(response: HttpServletResponse, error: ErrorCode) {
        response.status = error.httpStatus.value()
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        mapper.writeValue(response.writer, ApiResponse.error(error))
    }
}
