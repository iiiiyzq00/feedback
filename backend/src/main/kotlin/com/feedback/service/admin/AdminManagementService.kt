package com.feedback.service.admin

/**
 * HTTP 用户创建输入；角色字符串仅作为兼容边界，进入实现后立即解析为 RoleCode。
 *
 * [tenantKey] 是**这个账号属于哪个租户**，也就是会话主体的租户来源（`WebPrincipal.tenantKey`）。
 * 默认值是迁移期兼容租户：不传这个字段的既有调用方行为不变，落到 `DEFAULT`；
 * 显式传空串同样按 `DEFAULT` 处理，与接入侧对空租户的处理保持一致。
 * 传入未注册（或已停用）的租户会被拒绝——租户键不是自由字符串，见 V245。
 */
data class UserCreateInput(
    val username: String,
    val displayName: String?,
    val password: String,
    val roles: Set<String> = setOf(com.feedback.model.auth.RoleCode.USER.code),
    val scopes: List<DataScope> = emptyList(),
    val tenantKey: String = com.feedback.model.auth.TenantKeys.DEFAULT,
)

data class UserUpdateInput(val displayName: String?, val active: Boolean = true)
data class RoleAssignmentInput(val roles: Set<String>)
data class DataPermissionInput(val scopes: List<DataScope>)
data class RolePermissionInput(val permissions: Set<String>)

/** 数据范围 HTTP 输入；type/accessLevel/effect 保持既有字符串协议。 */
data class DataScope(
    val type: String,
    val value: String,
    val accessLevel: String = com.feedback.model.auth.DataAccessLevel.READ.code,
    val effect: String = com.feedback.model.auth.DataPermissionEffect.ALLOW.code,
)

/**
 * 用户、角色权限包与用户数据范围管理接口。
 *
 * Controller 只依赖本接口；实现负责最后管理员保护、授权目录校验、会话版本失效和安全审计原子性。
 */
interface AdminManagementService {
    /** 返回有界用户分页及批量装配的角色、飞书绑定和数据范围。 */
    fun users(page: Int, size: Int, keyword: String?): Map<String, Any>
    /** 返回有效角色、权限目录和当前角色权限矩阵。 */
    fun rolePermissionMatrix(): Map<String, Any>
    /** 原子替换角色权限包，并使全部当前角色成员旧会话失效后写审计。 */
    fun replaceRolePermissions(roleCode: String, permissions: Set<String>): Map<String, Any>
    /** 创建本地用户；BCrypt 在事务外，账号、角色、范围和审计在短事务内。 */
    fun createUser(input: UserCreateInput): Map<String, Any>
    /** 更新用户资料或启停状态，停用必须保护最后有效管理员。 */
    fun updateUser(id: Long, input: UserUpdateInput): Map<String, Any>
    /** 幂等停用用户并使旧会话失效，禁止停用自己或唯一有效管理员。 */
    fun disableUser(id: Long): Map<String, Any>
    /** 原子替换用户角色、提升 authVersion 并记录安全审计。 */
    fun assignRoles(id: Long, assigned: Set<String>): Map<String, Any>
    /** 原子替换用户数据范围、提升 authVersion 并记录安全审计。 */
    fun replaceDataPermissions(id: Long, scopes: List<DataScope>): Map<String, Any>
}
