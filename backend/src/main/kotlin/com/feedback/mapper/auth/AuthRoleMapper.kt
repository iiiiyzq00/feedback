package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthRolePo
import org.apache.ibatis.annotations.Mapper

/** 角色目录 Mapper；普通查询返回全部启用角色，锁查询用于授权变更前的事务内校验。 */
@Mapper
interface AuthRoleMapper {
    /** 按稳定展示顺序返回有效角色目录。 */
    fun findActive(): List<AuthRolePo>

    /** 锁定单个有效角色，作为权限包替换与用户角色写入的共同互斥点。 */
    fun lockActive(roleCode: String): AuthRolePo?

    /** 以稳定顺序锁定全部有效角色，作为用户角色变更与权限包变更的共同互斥栅栏。 */
    fun lockAllActive(): List<AuthRolePo>
}
