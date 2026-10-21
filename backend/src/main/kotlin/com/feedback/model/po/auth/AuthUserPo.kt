package com.feedback.model.po.auth

import com.feedback.model.auth.TenantKeys
import java.time.LocalDateTime

/**
 * 用户账号持久化模型，同时覆盖本地密码账号和飞书 SSO 账号。
 * passwordHash 仅本地账号需要；authVersion 每次失效会话时递增，并参与 Cookie 会话校验。
 * tenantKey 是租户隔离的读侧来源，由 V245 加入，默认值与数据库列一致。
 */
data class AuthUserPo(
    var id: Long? = null,
    val username: String,
    val displayName: String?,
    val avatarUrl: String? = null,
    val passwordHash: String? = null,
    val authVersion: Long = 0,
    val authProvider: String,
    val active: Boolean = true,
    val lastLoginAt: LocalDateTime? = null,
    val tenantKey: String = TenantKeys.DEFAULT,
)
