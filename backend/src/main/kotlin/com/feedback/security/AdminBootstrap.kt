package com.feedback.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import com.feedback.model.po.auth.AuthUserPo
import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.RoleCode
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * 可选的一次性本地管理员初始化器，便于全新环境首次登录；仓库中不内置任何默认凭据。
 * 仅当用户名和密码都由外部配置提供时执行，重复启动只幂等补齐 ADMIN 角色。
 */
@Component
class AdminBootstrap(
    private val users: AuthUserRepository,
    private val grants: AuthGrantRepository,
    private val encoder: PasswordEncoder,
    @Value("\${app.auth.bootstrap.username:}") private val username: String,
    @Value("\${app.auth.bootstrap.password:}") private val password: String
) : ApplicationRunner {
    /** 启动时校验外部凭据、按需创建 BCrypt 本地账号，并确保管理员角色存在。 */
    override fun run(args: ApplicationArguments) {
        if (username.isBlank() && password.isBlank()) {
            return
        }
        require(username.isNotBlank() && password.length >= 12) { "AUTH_BOOTSTRAP_CREDENTIALS_INVALID" }
        if (users.byUsername(username) == null) {
            users.createLocal(AuthUserPo(
                username = username, displayName = "Local Administrator",
                passwordHash = encoder.encode(password), authProvider = AuthProvider.LOCAL.code
            ))
        }
        val userId = users.byUsername(username)?.id ?: error("AUTH_BOOTSTRAP_USER_MISSING")
        grants.grantIfAbsent(userId, RoleCode.ADMIN)
    }
}
