package com.feedback.security

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/** 统一读取已认证 Web 主体；匿名或未完成认证的上下文失败关闭，业务服务不直接解析 Spring Security。 */
@Component
class CurrentWebPrincipalProvider {
    /** 返回当前已认证 Web 主体；匿名主体或其他认证通道的主体均按未登录处理。 */
    fun require(): WebPrincipal {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication?.isAuthenticated != true) {
            throw ClientException(ErrorCode.AUTH_UNAUTHORIZED, "AUTHENTICATION_REQUIRED")
        }
        return authentication.principal as? WebPrincipal
            ?: throw ClientException(ErrorCode.AUTH_UNAUTHORIZED, "AUTHENTICATION_REQUIRED")
    }
}
