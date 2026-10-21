package com.feedback.repository.impl.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.auth.AuthenticationAuditAction
import com.feedback.model.auth.AuthenticationFailureReason
import com.feedback.model.auth.AuthorizationAuditClass
import com.feedback.model.auth.AuthorizationAuditTarget
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.repository.auth.LoginAuditRepository
import com.feedback.repository.auth.SystemAuditMutationRepository
import org.springframework.stereotype.Repository
import java.security.MessageDigest

/**
 * 登录审计的 MyBatis 仓储实现。
 *
 * 用户名只以不可逆截断指纹进入审计数据。每个公开方法最终只执行一条 INSERT，因此这里不
 * 声明事务；成功事件会自然加入本地登录服务已经开启的事务。
 */
@Repository
class MyBatisLoginAuditRepositoryImpl(
    private val audits: SystemAuditMutationRepository,
    private val mapper: ObjectMapper,
) : LoginAuditRepository {
    /** 记录限流拒绝及触发维度；用户名仅以指纹保存，单条 INSERT 不单独开启事务。 */
    override fun recordBlocked(
        username: String,
        clientIp: String,
        accountLimited: Boolean,
        ipLimited: Boolean,
        windowSeconds: Long,
    ) {
        append(
            userId = null,
            operation = AuthenticationAuditAction.LOGIN_BLOCKED,
            username = username,
            clientIp = clientIp,
            fields = mapOf(
                "accountLimited" to accountLimited,
                "ipLimited" to ipLimited,
                "windowSeconds" to windowSeconds,
            ),
        )
    }

    /** 记录凭证校验失败及限流计数快照，不保存原始用户名或密码相关内容。 */
    override fun recordFailure(
        username: String,
        clientIp: String,
        accountAttempt: Long,
        ipAttempt: Long,
        windowSeconds: Long,
    ) {
        append(
            userId = null,
            operation = AuthenticationAuditAction.LOGIN_FAILED,
            username = username,
            clientIp = clientIp,
            fields = mapOf(
                "reason" to AuthenticationFailureReason.INVALID_CREDENTIALS.code,
                "accountAttempt" to accountAttempt,
                "ipAttempt" to ipAttempt,
                "windowSeconds" to windowSeconds,
            ),
        )
    }

    /** 记录成功登录；在本地登录成功服务中调用时加入其 CAS 短写事务。 */
    override fun recordSuccess(userId: Long, username: String, clientIp: String) {
        append(userId, AuthenticationAuditAction.LOGIN_SUCCESS, username, clientIp, emptyMap())
    }

    /** 构造统一安全审计记录；原始用户名不会写入 JSON、目标标识或其他列。 */
    private fun append(
        userId: Long?,
        operation: AuthenticationAuditAction,
        username: String,
        clientIp: String,
        fields: Map<String, Any>,
    ) {
        val usernameFingerprint = fingerprint(username.trim().lowercase())
        audits.append(
            SystemAuditInsertPo(
                userId = userId,
                operation = operation.code,
                auditClass = AuthorizationAuditClass.SECURITY.code,
                outcome = operation.outcome.code,
                targetType = AuthorizationAuditTarget.AUTH_ACCOUNT.code,
                targetId = usernameFingerprint,
                operationDetailJson = mapper.writeValueAsString(
                    fields + ("usernameFingerprint" to usernameFingerprint),
                ),
                ipAddress = clientIp.take(50),
            ),
        )
    }

    /** 生成不可逆截断指纹，使审计可关联同一账号但不能还原用户名。 */
    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }
}
