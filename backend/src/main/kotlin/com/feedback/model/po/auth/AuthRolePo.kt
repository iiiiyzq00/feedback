package com.feedback.model.po.auth

import java.time.LocalDateTime

/** 平台角色目录持久化投影；roleCode 进入业务层前必须按 RoleCode 失败关闭解析。 */
data class AuthRolePo(
    val roleCode: String,
    val roleName: String,
    val description: String?,
    val active: Boolean = true,
    val updatedAt: LocalDateTime? = null,
)
