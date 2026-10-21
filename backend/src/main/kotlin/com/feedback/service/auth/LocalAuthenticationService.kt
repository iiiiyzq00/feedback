package com.feedback.service.auth

import com.feedback.model.auth.AuthenticatedUserResult

/** 本地账号密码认证用例；接口不向 Web 层暴露 Spring Security 主体或数据库对象。 */
interface LocalAuthenticationService {
    /** 分阶段校验凭据，并通过短写事务完成登录时间更新与成功审计。 */
    fun authenticate(username: String, password: String, clientIp: String): AuthenticatedUserResult
}
