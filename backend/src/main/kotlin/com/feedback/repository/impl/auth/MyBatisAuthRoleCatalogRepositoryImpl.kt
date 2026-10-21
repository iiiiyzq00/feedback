package com.feedback.repository.impl.auth

import com.feedback.mapper.auth.AuthPermissionMapper
import com.feedback.mapper.auth.AuthRoleMapper
import com.feedback.model.auth.PermissionCode
import com.feedback.model.auth.RoleCode
import com.feedback.model.po.auth.AuthPermissionPo
import com.feedback.model.po.auth.AuthRolePo
import com.feedback.repository.auth.AuthRoleCatalogRepository
import org.springframework.stereotype.Repository

/** MyBatis 角色/权限目录实现；权限包写入前通过 `FOR UPDATE` 读取有效角色。 */
@Repository
class MyBatisAuthRoleCatalogRepositoryImpl(
    private val roles: AuthRoleMapper,
    private val permissions: AuthPermissionMapper,
) : AuthRoleCatalogRepository {
    /** 读取所有有效角色，供管理视图和边界校验使用。 */
    override fun active(): List<AuthRolePo> = roles.findActive()

    /** 以 `FOR UPDATE` 锁定有效角色，防止权限包更新与角色停用并发漂移。 */
    override fun lockActive(roleCode: RoleCode): AuthRolePo? = roles.lockActive(roleCode.code)

    /** 读取完整权限目录；权限码仍在仓储边界解析为值对象。 */
    override fun permissions(): List<AuthPermissionPo> = permissions.findAll()

    /** 按已校验权限码集合批量读取目录项，空集合直接返回。 */
    override fun permissions(codes: Set<PermissionCode>): List<AuthPermissionPo> = if (codes.isEmpty()) {
        emptyList()
    } else {
        permissions.findByCodes(codes.mapTo(linkedSetOf(), PermissionCode::code))
    }
}
