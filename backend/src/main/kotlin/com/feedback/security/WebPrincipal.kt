package com.feedback.security

import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.PermissionCode
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.TenantKeys
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * 平台 Web 用户在 Spring Security 中的不可变身份快照。
 * roles 转为 ROLE_ 权限供角色规则使用，permissions 保持原子权限名称；authVersion 用于废止旧会话。
 *
 * [tenantKey] 是租户隔离的**读侧**来源：它来自 `t_auth_user.tenant_key`，而不是会话或 Cookie。
 * 会话里只存 `userId + authVersion`（见 WebSessionStore），因此每次恢复会话都会重新装配主体、
 * 重新读到租户——这意味着一份已建立的会话**会**跟着账号的租户变更走，不需要额外失效机制。
 */
data class WebPrincipal(
    val userId: Long,
    private val loginName: String,
    private val passwordHash: String,
    val displayName: String?,
    val roles: Set<RoleCode>,
    val permissions: Set<PermissionCode>,
    val authVersion: Long = 0,
    val authProvider: AuthProvider = AuthProvider.LOCAL,
    val tenantKey: String = TenantKeys.DEFAULT,
) : UserDetails {
    /** 测试与迁移期调用方的兼容构造器；业务字符串在进入主体前即完成严格解析。 */
    constructor(
        userId: Long,
        loginName: String,
        passwordHash: String,
        displayName: String?,
        roles: Collection<String>,
        permissions: Collection<String>,
        authVersion: Long = 0,
        authProvider: String = AuthProvider.LOCAL.code,
        tenantKey: String = TenantKeys.DEFAULT,
    ) : this(
        userId,
        loginName,
        passwordHash,
        displayName,
        roles.mapTo(linkedSetOf(), RoleCode::requireStorage),
        permissions.mapTo(linkedSetOf(), PermissionCode::requireStorage),
        authVersion,
        AuthProvider.requireStorage(authProvider),
        tenantKey,
    )

    /** 合并角色 authority 与原子权限，供 URL 和方法级授权统一消费。 */
    override fun getAuthorities(): Collection<GrantedAuthority> =
        roles.map { SimpleGrantedAuthority("ROLE_${it.code}") } +
            permissions.map { SimpleGrantedAuthority(it.code) }

    /** 返回仅供 Spring 验密使用的密码哈希，不进入用户视图。 */
    override fun getPassword() = passwordHash

    /** 返回 Spring Security 使用的稳定登录名。 */
    override fun getUsername() = loginName

    /** 账号到期由数据库启用状态治理，已装配主体视为未到期。 */
    override fun isAccountNonExpired() = true

    /** 账号锁定通过启用状态和登录保护实现，已装配主体视为未锁定。 */
    override fun isAccountNonLocked() = true

    /** 凭证过期策略不在主体快照表达，已装配主体视为未过期。 */
    override fun isCredentialsNonExpired() = true

    /** 只有启用账号才能被装配，因此主体创建后始终返回 true。 */
    override fun isEnabled() = true
}
