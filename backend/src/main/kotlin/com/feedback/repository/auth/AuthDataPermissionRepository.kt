package com.feedback.repository.auth

import com.feedback.model.auth.DataAccessLevel
import com.feedback.model.auth.DataPermissionEffect
import com.feedback.model.auth.DataPermissionSubjectType
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.RoleCode

/**
 * 公共数据权限的单表读取边界。
 *
 * 角色数和返回行数均有硬上限；超限表示授权事实集合不完整，必须失败关闭，
 * 不能用截断结果意外扩大或缩小数据范围。
 */
interface AuthDataPermissionRepository {
    /** 一次有界读取用户和全部角色的 READ 授权；未知编码或溢出必须失败关闭。 */
    fun findReadPermissions(userId: Long, roles: Set<RoleCode>): List<AuthDataPermission>

    companion object {
        const val MAX_ROLE_COUNT = 32
        const val MAX_PERMISSION_ROWS = 1_000
    }
}

/** 授权计算使用的数据权限事实；字符串编码仅存在于 Mapper/PO 边界。 */
data class AuthDataPermission(
    val id: Long,
    val subjectType: DataPermissionSubjectType,
    val subjectId: String,
    val scopeType: DataScopeType,
    val scopeValue: String,
    val accessLevel: DataAccessLevel,
    val effect: DataPermissionEffect,
)
