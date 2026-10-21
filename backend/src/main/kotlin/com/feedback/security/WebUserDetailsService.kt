package com.feedback.security

import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.PermissionCode
import com.feedback.model.auth.RoleCode
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 从认证仓储装配 Spring Security 主体，是账号、角色与权限进入安全上下文的唯一入口。 */
@Service
class WebUserDetailsService(
    private val users: AuthUserRepository,
    private val grants: AuthGrantRepository
) : UserDetailsService {
    /** 本地密码认证按用户名加载启用账号；不存在时使用统一错误避免账号枚举。 */
    @Transactional(readOnly = true)
    override fun loadUserByUsername(username: String): WebPrincipal = assemble(users.activeByUsername(username))

    /** Cookie 会话和飞书登录按不可猜测的内部用户 ID 恢复启用账号。 */
    @Transactional(readOnly = true)
    fun loadUserById(userId: Long): WebPrincipal = assemble(users.activeById(userId))

    /**
     * 依次读取用户、有效角色和角色权限并组装主体；公开入口的只读事务保证三次相关查询看到
     * 一致快照，同时角色权限查询按角色集合批量完成，避免逐角色 N+1。
     *
     * 租户随账号快照一起进入主体。这里传的是 `AuthUserPo.tenantKey` 的**原始值**，不做规范化：
     * 规范化发生在范围判定处（TenantKeys.canonical），一处实现即可；
     * 在边界再规范化一次会让「主体里的租户」与「数据库里的租户」出现两种写法，反而难以对账。
     */
    private fun assemble(user: com.feedback.model.po.auth.AuthUserPo?): WebPrincipal {
        user ?: throw UsernameNotFoundException("AUTH_INVALID_CREDENTIALS")
        val userId = checkNotNull(user.id)
        val roles = grants.activeRoles(userId)
        val permissions = grants.permissions(roles)
        return WebPrincipal(
            userId, user.username, user.passwordHash.orEmpty(), user.displayName, roles, permissions,
            user.authVersion, AuthProvider.requireStorage(user.authProvider), user.tenantKey
        )
    }
}
