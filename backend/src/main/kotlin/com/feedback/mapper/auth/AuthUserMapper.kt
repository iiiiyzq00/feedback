package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthUserPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 用户表 Mapper。
 *
 * 提供登录查询、用户管理写入、SSO 资料刷新和 authVersion 递增；findActive* 只返回可登录账号，
 * incrementAuthVersion 使用单条 SQL 原子废止用户既有会话；事务边界由实际业务交互而非表数量决定。
 */
@Mapper
interface AuthUserMapper {
    /** 按规范化用户名读取启用账号，登录失败统一由上层处理以避免账号枚举。 */
    fun findActiveByUsername(username: String): AuthUserPo?

    /** 按内部 ID 读取启用账号，用于 Cookie、SSO 短码和授权主体恢复。 */
    fun findActiveById(id: Long): AuthUserPo?

    /** 读取账号而不限制启用状态，仅供管理和身份绑定判断。 */
    fun findByUsername(username: String): AuthUserPo?

    /** 授权管理写入前锁定用户行，确保启停、角色与 authVersion 基于同一当前状态。 */
    fun lockById(id: Long): AuthUserPo?

    /** 使用明确 LIMIT/OFFSET 返回管理页账号，不附带角色或范围 N+1。 */
    fun findPage(
        @Param("keyword") keyword: String?,
        @Param("offset") offset: Long,
        @Param("size") size: Int,
    ): List<AuthUserPo>

    /** 按与分页查询相同的关键字口径统计总数。 */
    fun count(@Param("keyword") keyword: String?): Long

    /** 按已限定 ID 集合批量读取账号；调用方负责空集合短路。 */
    fun findByIds(@Param("ids") ids: Set<Long>): List<AuthUserPo>

    /** 创建本地账号并回填主键；密码必须已在事务外完成 BCrypt。 */
    fun insertLocal(user: AuthUserPo): Int

    /** 创建无本地密码的 SSO 账号并回填主键。 */
    fun insertSso(user: AuthUserPo): Int

    /** 原子更新最近登录时间，影响行数必须由仓储校验。 */
    fun touchLastLogin(id: Long): Int

    /** 仅当本地认证快照仍有效时记录登录时间，防止验密期间账号状态或授权版本发生变化。 */
    fun touchLastLoginIfAuthenticationCurrent(
        @Param("id") id: Long,
        @Param("expectedAuthVersion") expectedAuthVersion: Long,
    ): Int

    /** 单条 SQL 原子提升用户 authVersion，使此前签发的全部会话失效。 */
    fun incrementAuthVersion(id: Long): Int

    /** 按角色关联集合式提升版本，避免把无界用户 ID 集合拉入 JVM 或拼成巨大 IN。 */
    fun incrementAuthVersionByRole(@Param("roleCode") roleCode: String): Int

    /** 更新管理资料，并仅在启用状态变化时原子提升 authVersion。 */
    fun updateAdminProfile(
        @Param("id") id: Long,
        @Param("displayName") displayName: String?,
        @Param("active") active: Boolean,
    ): Int

    /** 仅停用仍启用账号并提升 authVersion；0 表示已停用或不存在。 */
    fun disableActive(id: Long): Int

    /** 刷新 SSO 展示资料；不得改变角色、权限或认证来源。 */
    fun updateSsoProfile(
        @Param("id") id: Long,
        @Param("displayName") displayName: String,
        @Param("avatarUrl") avatarUrl: String?,
    ): Int

    /** 在飞书授权同步中写启用状态并提升 authVersion，确保旧会话同时失效。 */
    fun updateSsoActiveAndVersion(
        @Param("id") id: Long,
        @Param("active") active: Boolean,
    ): Int
}
