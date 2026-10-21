package com.feedback.service.impl.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.config.LocalRegistrationProperties
import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.AuthorizationAuditAction
import com.feedback.model.auth.AuthorizationAuditClass
import com.feedback.model.auth.AuthorizationAuditOutcome
import com.feedback.model.auth.AuthorizationAuditTarget
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.TenantKeys
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.model.po.auth.AuthUserPo
import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.repository.auth.SystemAuditMutationRepository
import com.feedback.service.auth.AuthRegistrationService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

/**
 * 本地自助注册实现。
 *
 * 与后台建号（AdminManagementServiceImpl.createUser）共用同一套口令策略、租户口径与 BCrypt 成本因子，
 * 差别只有两处**收紧**：注册者不能自选角色（固定 [RoleCode.USER]），也不能指定租户（固定默认租户）。
 * BCrypt 在事务外执行，随后的账号 + 授权 + 审计三写在一个短事务里整体提交。
 */
@Service
class AuthRegistrationServiceImpl(
    private val properties: LocalRegistrationProperties,
    private val passwords: PasswordEncoder,
    private val authUsers: AuthUserRepository,
    private val authGrants: AuthGrantRepository,
    private val auditMutations: SystemAuditMutationRepository,
    private val mapper: ObjectMapper,
    private val transactions: TransactionOperations,
) : AuthRegistrationService {

    /** 按平台策略校验入参并建档；注册入口关闭时按资源不存在处理。 */
    override fun register(username: String, password: String, displayName: String?, clientIp: String): Long {
        if (!properties.enabled) {
            throw ClientException(ErrorCode.NOT_FOUND, "AUTH_REGISTRATION_DISABLED")
        }
        val name = username.trim()
        if (!USERNAME_PATTERN.matches(name)) {
            throw ClientException(ErrorCode.BAD_REQUEST, "AUTH_REGISTRATION_USERNAME_INVALID")
        }
        if (displayName != null && displayName.length > 100) {
            throw ClientException(ErrorCode.BAD_REQUEST, "AUTH_REGISTRATION_DISPLAY_NAME_INVALID")
        }
        if (password.length !in PASSWORD_MIN..PASSWORD_MAX) {
            throw ClientException(ErrorCode.BAD_REQUEST, "AUTH_REGISTRATION_PASSWORD_INVALID")
        }
        // BCrypt 明确在事务外执行：它只依赖入参，不应占用数据库连接做高成本 CPU 计算。
        val passwordHash = passwords.encode(password)
        val display = displayName?.trim()?.takeIf(String::isNotEmpty)
        return transactions.execute {
            // 同名判定放在事务内：唯一索引是最后一道防线，但显式判定才能给出稳定的用户可读错误码。
            if (authUsers.byUsername(name) != null) {
                throw ClientException(ErrorCode.BAD_REQUEST, "AUTH_REGISTRATION_USERNAME_TAKEN")
            }
            val id = authUsers.createLocal(
                AuthUserPo(
                    username = name,
                    displayName = display,
                    passwordHash = passwordHash,
                    authProvider = AuthProvider.LOCAL.code,
                    tenantKey = TenantKeys.DEFAULT,
                ),
            )
            authGrants.grantIfAbsent(id, RoleCode.USER)
            auditMutations.append(
                SystemAuditInsertPo(
                    // 自助注册没有独立的操作者：注册者本人就是被创建对象，用新账号 ID 记载行为主体。
                    userId = id,
                    operation = AuthorizationAuditAction.AUTH_USER_CREATED.code,
                    auditClass = AuthorizationAuditClass.SECURITY.code,
                    outcome = AuthorizationAuditOutcome.SUCCESS.code,
                    targetType = AuthorizationAuditTarget.AUTH_USER.code,
                    targetId = id.toString(),
                    operationDetailJson = mapper.writeValueAsString(
                        mapOf(
                            "roles" to listOf(RoleCode.USER.code),
                            "tenantKey" to TenantKeys.DEFAULT,
                            "source" to "LOCAL_SELF_REGISTRATION",
                        ),
                    ),
                    ipAddress = clientIp,
                ),
            )
            id
        } ?: error("AUTH_REGISTRATION_TRANSACTION_EMPTY")
    }

    private companion object {
        /** 与后台建号完全相同的账号名形状。 */
        val USERNAME_PATTERN = Regex("^[A-Za-z0-9_.-]{3,50}$")
        const val PASSWORD_MIN = 12
        const val PASSWORD_MAX = 128
    }
}
