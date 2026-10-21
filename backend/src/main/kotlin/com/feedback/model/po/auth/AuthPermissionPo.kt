package com.feedback.model.po.auth

/** 可扩展权限目录的持久化投影；permissionCode 进入业务层前必须转换为 PermissionCode。 */
data class AuthPermissionPo(
    val permissionCode: String,
    val permissionName: String,
    val permissionType: String,
    val module: String?,
    val resourcePath: String?,
    val sortOrder: Int,
)
