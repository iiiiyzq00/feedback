package com.feedback.service.impl.auth

import com.feedback.common.exception.AuthenticationSnapshotChangedException

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.AuthenticatedUserResult
import com.feedback.service.auth.LocalAuthenticationService
import com.feedback.service.auth.LocalLoginSuccessService
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.stereotype.Service

/**
 * 本地账号密码认证实现。
 *
 * AuthenticationManager 加载主体时进入短只读事务，返回后在事务外执行 BCrypt；验证成功后调用
 * [LocalLoginSuccessService] 开启短写事务。Redis 限流、Cookie/Redis 会话签发同样位于事务之外。
 */
@Service
class LocalAuthenticationServiceImpl(
    private val authenticationManager: AuthenticationManager,
    private val success: LocalLoginSuccessService,
) : LocalAuthenticationService {
    /**
     * 在事务外完成验密，并以主体中的 authVersion 提交登录成功记录。
     * 若验密期间授权快照变化则重新装配主体，最多尝试两次，避免旧权限主体被签发为新会话。
     */
    override fun authenticate(username: String, password: String, clientIp: String): AuthenticatedUserResult {
        repeat(MAX_AUTHENTICATION_ATTEMPTS) { attempt ->
            val authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(username, password),
            )
            val principal = authentication.principal as WebPrincipal
            try {
                success.commit(principal.userId, principal.username, principal.authVersion, clientIp)
                return principal.toResult()
            } catch (changed: AuthenticationSnapshotChangedException) {
                // 极小概率的并发授权变更重新读取一次；限制次数避免持续变更导致无界 BCrypt 消耗。
                if (attempt == MAX_AUTHENTICATION_ATTEMPTS - 1) {
                    // 这是并发状态冲突而非错误密码，不应进入控制器的失败计数分支。
                    throw ServiceException(
                        ErrorCode.INTERNAL_ERROR,
                        "AUTHENTICATION_SNAPSHOT_RETRY_EXHAUSTED",
                        cause = changed,
                    )
                }
            }
        }
        error("unreachable")
    }

    /** 保留强类型角色、权限和认证来源，将已校验主体转换为登录编排结果。 */
    private fun WebPrincipal.toResult() = AuthenticatedUserResult(
        userId = userId,
        username = username,
        displayName = displayName,
        roles = roles,
        permissions = permissions,
        authVersion = authVersion,
        authProvider = authProvider,
    )

    private companion object {
        const val MAX_AUTHENTICATION_ATTEMPTS = 2
    }
}
