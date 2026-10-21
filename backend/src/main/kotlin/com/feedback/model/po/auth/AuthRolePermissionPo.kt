package com.feedback.model.po.auth

/** 角色到原子权限的持久化关联，登录时展开进 WebPrincipal.authorities。 */
data class AuthRolePermissionPo(
    val roleCode: String,
    val permissionCode: String,
)
