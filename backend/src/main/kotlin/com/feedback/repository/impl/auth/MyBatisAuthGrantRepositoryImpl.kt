package com.feedback.repository.impl.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.auth.AuthRolePermissionMapper
import com.feedback.mapper.auth.AuthRoleMapper
import com.feedback.mapper.auth.AuthUserRoleMapper
import com.feedback.model.auth.PermissionCode
import com.feedback.model.auth.RoleCode
import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.RolePermissionGrant
import org.springframework.stereotype.Repository

/**
 * MyBatis 角色授权仓储实现。
 *
 * 角色集合与权限集合均批量读取；删除后重建必须加入上层短写事务，不在此处隐式开启事务。
 */
@Repository
class MyBatisAuthGrantRepositoryImpl(
    private val roles: AuthUserRoleMapper,
    private val permissions: AuthRolePermissionMapper,
    private val roleCatalog: AuthRoleMapper,
) : AuthGrantRepository {
    /** 读取用户当前有效角色并严格解析数据库稳定 code；未知编码失败关闭。 */
    override fun activeRoles(userId: Long): Set<RoleCode> = roles.findActiveByUserId(userId)
        .mapTo(linkedSetOf()) { RoleCode.requireStorage(it.roleCode) }

    /** 读取用户全部角色，用于授权管理和同步差异比较。 */
    override fun roles(userId: Long): Set<RoleCode> = roles.findByUserId(userId)
        .mapTo(linkedSetOf()) { RoleCode.requireStorage(it.roleCode) }

    /** 批量读取多个用户的角色，空集合短路以避免非法 SQL 和逐用户 N+1。 */
    override fun rolesByUsers(userIds: Set<Long>): Map<Long, List<RoleCode>> = if (userIds.isEmpty()) {
        emptyMap()
    } else {
        roles.findByUserIds(userIds).groupBy({ it.userId }, { RoleCode.requireStorage(it.roleCode) })
    }

    /** 使用数据库聚合统计有效角色成员；必须在角色栅栏锁内用于最后管理员判断。 */
    override fun activeUserCount(roleCode: RoleCode): Long = roles.countActiveUsersByRoleCode(roleCode.code)

    /** 锁定全部有效角色并严格解析 code；授权写入统一在获取该栅栏后再锁用户行。 */
    override fun lockRoleMutationFence(): Set<RoleCode> = roleCatalog.lockAllActive()
        .mapTo(linkedSetOf()) { RoleCode.requireStorage(it.roleCode) }

    /** 按角色集合一次批量合并权限；空角色不授予任何权限。 */
    override fun permissions(roleCodes: Set<RoleCode>): Set<PermissionCode> = if (roleCodes.isEmpty()) {
        emptySet()
    } else {
        permissions.findByRoleCodes(roleCodes.mapTo(linkedSetOf(), RoleCode::code))
            .mapTo(linkedSetOf()) { PermissionCode.requireStorage(it.permissionCode) }
    }

    /** 插入单条角色授权并校验影响行数；写入失败触发上层事务回滚。 */
    override fun grant(userId: Long, roleCode: RoleCode, assignedBy: Long?) {
        persisted(roles.insert(userId, roleCode.code, assignedBy) == 1, "AUTH_ROLE_GRANT_FAILED")
    }

    /** 幂等授予默认角色，依赖数据库唯一键和 INSERT IGNORE 消解并发重复。 */
    override fun grantIfAbsent(userId: Long, roleCode: RoleCode) {
        roles.insertIgnore(userId, roleCode.code)
    }

    /** 以单角色替换用户授权；删除与新增必须由调用方短写事务包裹。 */
    override fun replace(userId: Long, roleCode: RoleCode) {
        roles.deleteByUserId(userId)
        grant(userId, roleCode)
    }

    /** 按稳定顺序重建用户角色集合；调用方负责同事务提升 authVersion 并写审计。 */
    override fun replace(userId: Long, roleCodes: Set<RoleCode>, assignedBy: Long) {
        roles.deleteByUserId(userId)
        roleCodes.sortedBy(RoleCode::code).forEach { grant(userId, it, assignedBy) }
    }

    /** 批量读取完整角色权限矩阵并转换为强类型授权记录。 */
    override fun allRolePermissions(): List<RolePermissionGrant> = permissions.findAll().map {
        RolePermissionGrant(RoleCode.requireStorage(it.roleCode), PermissionCode.requireStorage(it.permissionCode))
    }

    /** 重建角色权限包；必须加入包含受影响用户会话失效和审计的上层事务。 */
    override fun replaceRolePermissions(roleCode: RoleCode, permissionCodes: Set<PermissionCode>, actor: Long) {
        permissions.deleteByRoleCode(roleCode.code)
        permissionCodes.sorted().forEach {
            persisted(permissions.insert(roleCode.code, it.code, actor) == 1, "AUTH_ROLE_PERMISSION_INSERT_FAILED")
        }
    }

    /** 校验写入影响行数，不允许授权操作静默部分成功。 */
    private fun persisted(condition: Boolean, reasonCode: String) {
        if (!condition) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
        }
    }
}
