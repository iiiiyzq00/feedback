package com.feedback.model.po.auth

/** `t_auth_data_permission` 的单表持久化对象，不承载权限合并规则。 */
data class AuthDataPermissionPo(
    val id: Long,
    val subjectType: String,
    val subjectId: String,
    val scopeType: String,
    val scopeValue: String,
    val accessLevel: String,
    val effect: String,
)
