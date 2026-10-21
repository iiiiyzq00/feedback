package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthRolePermissionPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 角色权限关联表 Mapper。
 *
 * 登录主体按角色集合一次展开原子权限，避免逐角色 N+1；删除后重建权限必须由调用方事务覆盖。
 */
@Mapper
interface AuthRolePermissionMapper {
    /** 一次展开主体全部角色权限，调用方保证角色集合非空以避免非法 IN。 */
    fun findByRoleCodes(@Param("roleCodes") roleCodes: Set<String>): List<AuthRolePermissionPo>

    /** 返回完整角色权限矩阵供管理端展示。 */
    fun findAll(): List<AuthRolePermissionPo>

    /** 删除角色当前权限包；必须与重建、会话失效和审计同事务。 */
    fun deleteByRoleCode(roleCode: String): Int

    /** 插入一条角色权限及授权人，影响行数由仓储严格校验。 */
    fun insert(
        @Param("roleCode") roleCode: String,
        @Param("permissionCode") permissionCode: String,
        @Param("grantedBy") grantedBy: Long,
    ): Int
}
