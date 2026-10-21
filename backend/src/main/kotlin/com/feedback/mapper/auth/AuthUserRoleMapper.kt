package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthUserRolePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 用户角色关联表 Mapper。
 *
 * 登录时读取未过期角色；删除后重建角色属于多语句业务操作，必须由调用方事务统一覆盖。
 */
@Mapper
interface AuthUserRoleMapper {
    /** 读取用户当前未过期角色，供登录主体和会话恢复装配。 */
    fun findActiveByUserId(userId: Long): List<AuthUserRolePo>

    /** 读取用户完整角色集合，供管理写入差异判断。 */
    fun findByUserId(userId: Long): List<AuthUserRolePo>

    /** 按一页用户批量装配角色，调用方保证集合非空，避免 N+1。 */
    fun findByUserIds(@Param("userIds") userIds: Set<Long>): List<AuthUserRolePo>

    /** 统计仍启用且拥有指定角色的用户，供最后管理员保护使用。 */
    fun countActiveUsersByRoleCode(roleCode: String): Long

    /** 插入带授权人的角色关联；重复授权由唯一键拒绝并触发事务回滚。 */
    fun insert(
        @Param("userId") userId: Long,
        @Param("roleCode") roleCode: String,
        @Param("assignedBy") assignedBy: Long?,
    ): Int

    /** 幂等写入首次 SSO 登录的默认角色，依赖主键消解并发重复。 */
    fun insertIgnore(
        @Param("userId") userId: Long,
        @Param("roleCode") roleCode: String,
    ): Int

    /** 删除用户全部角色；必须与后续重建和 authVersion 提升同事务。 */
    fun deleteByUserId(userId: Long): Int
}
