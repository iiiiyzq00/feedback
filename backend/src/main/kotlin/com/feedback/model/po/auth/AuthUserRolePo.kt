package com.feedback.model.po.auth

import java.time.LocalDateTime

/** 用户与角色的持久化关联；expiresAt 为空表示长期有效，否则登录装配时只读取未过期角色。 */
data class AuthUserRolePo(
    val userId: Long,
    val roleCode: String,
    val expiresAt: LocalDateTime? = null,
)
