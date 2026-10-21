package com.feedback.repository.impl.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.auth.AuthExternalIdentityPo
import com.feedback.model.po.auth.AuthSsoLoginCodePo
import com.feedback.model.po.auth.AuthSsoStatePo
import com.feedback.model.po.auth.AuthUserPo
import com.feedback.model.result.auth.FeishuIdentity
import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.RoleCode
import com.feedback.repository.auth.AuthExternalIdentityRepository
import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.AuthSsoRepository
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.repository.auth.FeishuLoginCodeState
import com.feedback.repository.auth.FeishuPendingState
import com.feedback.repository.auth.FeishuSsoRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/**
 * 基于 MyBatis 的飞书登录仓储实现。
 *
 * 聚合 OAuth state、登录短码、用户、角色和外部身份数据；上层只看到一次性凭证和身份绑定语义，
 * 不感知 PO、条件更新、MySQL 行锁或新用户最小权限初始化。
 */
@Repository
class MyBatisFeishuSsoRepositoryImpl(
    private val sso: AuthSsoRepository,
    private val users: AuthUserRepository,
    private val grants: AuthGrantRepository,
    private val identities: AuthExternalIdentityRepository,
) : FeishuSsoRepository {
    /** 单条 INSERT 保存 OAuth state，不额外创建事务。 */
    override fun createState(stateHash: ByteArray, returnPath: String, redirectUri: String, ttlSeconds: Long) {
        sso.createState(AuthSsoStatePo(stateHash, returnPath, redirectUri, ttlSeconds))
    }

    /**
     * 远程换票前只读取 state 上下文，不在 HTTP 调用期间保留事务或行锁；
     * [completeCallback] 会在本地短事务中重新锁定并确认其仍可消费。
     */
    override fun findPendingState(stateHash: ByteArray): FeishuPendingState? = sso.findUsableState(stateHash)?.let {
        FeishuPendingState(it.returnPath, it.redirectUri.orEmpty())
    }

    /**
     * 二次读取并锁定 state，防止两个已完成远程交换的并发回调同时创建登录码。
     * 远程 HTTP 已在进入本方法前完成，因此事务只覆盖本地身份绑定和一次性消费。
     */
    @Transactional
    override fun completeCallback(
        stateHash: ByteArray,
        identity: FeishuIdentity,
        loginCodeHash: ByteArray,
        loginCodeTtlSeconds: Long,
    ) {
        val state = sso.lockState(stateHash)
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "FEISHU_STATE_ALREADY_USED")
        if (sso.consumeState(stateHash) != 1) {
            throw ClientException(ErrorCode.BAD_REQUEST, "FEISHU_STATE_ALREADY_USED")
        }

        val userId = bindIdentity(identity)
        sso.createLoginCode(AuthSsoLoginCodePo(loginCodeHash, userId, state.returnPath, loginCodeTtlSeconds))
    }

    /**
     * 锁定并消费登录短码；事务由 FeishuSsoServiceImpl 统一开启，
     * 使短码消费和最后登录时间更新处于同一提交边界，避免嵌套重复事务声明。
     */
    override fun consumeLoginCode(codeHash: ByteArray): FeishuLoginCodeState {
        val row = sso.lockLoginCode(codeHash)
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "FEISHU_LOGIN_CODE_INVALID_OR_EXPIRED")
        if (sso.consumeLoginCode(codeHash) != 1) {
            throw ClientException(ErrorCode.BAD_REQUEST, "FEISHU_LOGIN_CODE_ALREADY_USED")
        }
        return FeishuLoginCodeState(row.userId, row.returnPath)
    }

    /** 单条 UPDATE 记录本次登录时间，由上层短码兑换事务统一提交。 */
    override fun updateLastLogin(userId: Long) {
        users.touchLastLogin(userId)
    }

    /**
     * 按飞书 openId 查找或创建本地用户，并刷新外部身份资料。
     * 新用户只授予最小 USER 角色；登录顺序和飞书资料本身都不能自动提升权限。
     */
    private fun bindIdentity(identity: FeishuIdentity): Long {
        val existing = identities.feishuBySubject(identity.openId)
        val userId = if (existing != null) {
            existing.userId
        } else {
            val activeRoles = grants.lockRoleMutationFence()
            if (RoleCode.USER !in activeRoles) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "AUTH_DEFAULT_ROLE_UNAVAILABLE")
            }
            val username = "feishu_" + MessageDigest.getInstance("SHA-256")
                .digest(identity.openId.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
                .take(24)
            val id = users.createSso(
                AuthUserPo(
                    username = username,
                    displayName = identity.name.take(100),
                    avatarUrl = identity.avatarUrl?.take(500),
                    authProvider = AuthProvider.SSO.code,
                ),
            )
            grants.grantIfAbsent(id, RoleCode.USER)
            identities.create(
                AuthExternalIdentityPo(
                    userId = id,
                    providerSubject = identity.openId,
                    unionId = identity.unionId,
                    employeeId = identity.employeeId,
                    tenantKey = identity.tenantKey,
                ),
            )
            id
        }
        users.refreshSsoProfile(userId, identity.name.take(100), identity.avatarUrl?.take(500))
        identities.refresh(
            AuthExternalIdentityPo(
                userId = userId,
                providerSubject = identity.openId,
                unionId = identity.unionId,
                employeeId = identity.employeeId,
                tenantKey = identity.tenantKey,
            ),
        )
        return userId
    }
}
