package com.feedback.model.po.auth

/** 管理端批量展示的数据范围持久化投影；字符串仅用于 MyBatis/HTTP 兼容边界。 */
data class AuthAdminDataScopePo(
    val subjectId: String,
    val type: String,
    val value: String,
    val accessLevel: String,
    val effect: String,
)
