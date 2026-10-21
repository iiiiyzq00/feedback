package com.feedback.service.impl.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.AuthorizationAuditAction
import com.feedback.model.auth.AuthorizationAuditClass
import com.feedback.model.auth.AuthorizationAuditOutcome
import com.feedback.model.auth.AuthorizationAuditTarget
import com.feedback.model.auth.DataAccessLevel
import com.feedback.model.auth.DataPermissionEffect
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.DataScopeValues
import com.feedback.model.auth.PermissionCode
import com.feedback.model.auth.PlatformPermissions
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.TenantKeys
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.model.po.auth.AuthUserPo
import com.feedback.repository.auth.AuthAdminDataPermissionRepository
import com.feedback.repository.auth.AuthDataScopeCommand
import com.feedback.repository.auth.AuthExternalIdentityRepository
import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.AuthRoleCatalogRepository
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.repository.auth.SystemAuditMutationRepository
import com.feedback.repository.auth.TenantRegistryRepository
import com.feedback.security.WebPrincipal
import com.feedback.service.admin.AdminManagementService
import com.feedback.service.admin.DataScope
import com.feedback.service.admin.UserCreateInput
import com.feedback.service.admin.UserUpdateInput
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations

/**
 * 权限管理服务实现。
 *
 * 角色/权限/数据范围变更、受影响用户 authVersion 提升及安全审计处于同一短写事务；任何一步失败均回滚。
 * BCrypt 在事务外完成，之后用独立本地事务创建账号及最小授权，避免高成本 CPU 占用连接。
 */
@Service
class AdminManagementServiceImpl(
    private val mapper: ObjectMapper,
    private val passwords: PasswordEncoder,
    private val authUsers: AuthUserRepository,
    private val authGrants: AuthGrantRepository,
    private val externalIdentities: AuthExternalIdentityRepository,
    private val roleCatalog: AuthRoleCatalogRepository,
    private val adminScopes: AuthAdminDataPermissionRepository,
    private val auditMutations: SystemAuditMutationRepository,
    private val tenants: TenantRegistryRepository,
    private val transactions: TransactionOperations,
) : AdminManagementService {
    private val feedbackRoles = setOf(RoleCode.PHASE_ONE_TECHNICAL, RoleCode.PHASE_ONE_PRODUCT)
    private val assignableRoles = setOf(
        RoleCode.ADMIN,
        RoleCode.OPERATOR,
        RoleCode.AUDITOR,
        RoleCode.USER,
        RoleCode.PHASE_ONE_TECHNICAL,
        RoleCode.PHASE_ONE_PRODUCT,
    )

    /** 分页批量装配用户、角色、飞书绑定和数据范围，相关查询共享短只读事务且不产生 N+1。 */
    @Transactional(readOnly = true)
    override fun users(page: Int, size: Int, keyword: String?): Map<String, Any> {
        val p = page.coerceAtLeast(1)
        val s = size.coerceIn(1, 100)
        val offset = (p.toLong() - 1L) * s
        val search = keyword?.trim()?.takeIf(String::isNotBlank)?.take(100)
        val rows = authUsers.page(search, offset, s)
        val ids = rows.mapNotNull { it.id }.toSet()
        val rolesByUser = authGrants.rolesByUsers(ids)
        val feishuUsers = externalIdentities.feishuByUserIds(ids).map { it.userId }.toSet()
        val scopesByUser = adminScopes.byUserIds(ids)
        val items = rows.map { user ->
            linkedMapOf<String, Any?>(
                "id" to user.id,
                "username" to user.username,
                "realName" to user.displayName,
                "authProvider" to AuthProvider.requireStorage(user.authProvider).code,
                "active" to user.active,
                "lastLoginAt" to user.lastLoginAt,
                "roles" to rolesByUser[user.id].orEmpty().map(RoleCode::code).sorted(),
                "feishuBound" to (user.id in feishuUsers),
                "dataScopes" to scopesByUser[user.id.toString()].orEmpty().map { scope ->
                    mapOf(
                        "type" to DataScopeType.requireStorage(scope.type).code,
                        "value" to scope.value,
                        "accessLevel" to DataAccessLevel.requireStorage(scope.accessLevel).code,
                        "effect" to DataPermissionEffect.requireStorage(scope.effect).code,
                    )
                },
            )
        }
        return page(items, authUsers.count(search), p, s)
    }

    /** 在同一只读快照中返回有效角色、权限目录及授权矩阵，边界只输出稳定 code。 */
    @Transactional(readOnly = true)
    override fun rolePermissionMatrix(): Map<String, Any> {
        val roleRows = roleCatalog.active()
        val grants = authGrants.allRolePermissions().groupBy({ it.roleCode }, { it.permissionCode })
        val permissionRows = roleCatalog.permissions().map {
            mapOf(
                "permissionCode" to PermissionCode.requireStorage(it.permissionCode).code,
                "permissionName" to it.permissionName,
                "permissionType" to it.permissionType,
                "module" to it.module,
                "resourcePath" to it.resourcePath,
                "sortOrder" to it.sortOrder,
            )
        }
        val assigned = grants.values.flatten().toSet()
        return mapOf(
            "roles" to roleRows.map {
                val role = RoleCode.requireStorage(it.roleCode)
                mapOf(
                    "roleCode" to role.code,
                    "roleName" to it.roleName,
                    "description" to it.description,
                    "active" to it.active,
                    "updatedAt" to it.updatedAt,
                    "permissions" to grants[role].orEmpty().map(PermissionCode::code).sorted(),
                )
            },
            "permissionGroups" to permissionRows.groupBy { it["module"]?.toString() ?: "LEGACY" }
                .map { (module, values) -> mapOf("module" to module, "permissions" to values) },
            "unassignedPermissions" to permissionRows.filter {
                PermissionCode.requireStorage(it["permissionCode"].toString()) !in assigned
            }.map { it["permissionCode"] },
        )
    }

    /** 权限包、全部受影响用户会话失效和审计必须共同提交。 */
    @Transactional
    override fun replaceRolePermissions(roleCode: String, permissions: Set<String>): Map<String, Any> {
        val role = requireAssignableRole(roleCode)
        val requested = permissions.mapTo(linkedSetOf(), PermissionCode::requireBoundary)
        require(requested.isNotEmpty()) { "角色至少需要一个权限" }
        if (role == RoleCode.PHASE_ONE_PRODUCT) {
            require(requested == setOf(PlatformPermissions.WORKSPACE)) {
                "反馈产品角色权限固定，不允许修改"
            }
        }
        if (role == RoleCode.PHASE_ONE_TECHNICAL) {
            require(requested == setOf(PlatformPermissions.WORKSPACE, PlatformPermissions.RAW_CONTENT)) {
                "反馈技术角色权限固定，不允许修改"
            }
        }
        if (role == RoleCode.ADMIN) {
            require(PlatformPermissions.USER_MANAGEMENT in requested && PlatformPermissions.ADMIN_MANAGE in requested) {
                "管理员必须保留用户管理权限"
            }
        }
        // 先取得角色互斥点，再读取目录和关联用户，避免事务快照早于并发角色分配提交。
        require(roleCatalog.lockActive(role) != null) { "角色不存在" }
        val known = roleCatalog.permissions(requested).map { PermissionCode.requireStorage(it.permissionCode) }.toSet()
        require(known == requested) { "包含未注册权限码" }
        val actor = actorId()
        authGrants.replaceRolePermissions(role, requested, actor)
        val affected = authUsers.invalidateSessionsByRole(role)
        audit(actor, AuthorizationAuditAction.ROLE_PERMISSIONS_REPLACED, AuthorizationAuditTarget.AUTH_ROLE, role.code, mapOf(
            "permissions" to requested.map(PermissionCode::code).sorted(),
            "affectedUsers" to affected,
        ))
        return mapOf(
            "roleCode" to role.code,
            "permissions" to requested.map(PermissionCode::code).sorted(),
            "revokedSessions" to true,
        )
    }

    /**
     * 先在事务外执行 BCrypt，再在独立短写事务中创建本地账号、最小授权、数据范围和审计。
     * 数据库阶段任一步失败均整体回滚，不会留下无角色账号。
     */
    override fun createUser(input: UserCreateInput): Map<String, Any> {
        require(input.username.matches(Regex("^[A-Za-z0-9_.-]{3,50}$"))) { "用户名格式非法" }
        require(input.displayName == null || input.displayName.length <= 100)
        require(input.password.length in 12..128) { "密码至少 12 位" }
        val roles = parseRoles(input.roles)
        val scopes = input.scopes.map(::normalizeScope)
        // 租户在事务外规范化：它是纯字符串处理，与库状态无关。空值（含未传）按迁移期兼容租户处理，
        // 与 RcaIntakeSignalAssembler 对空租户的口径一致——两处对「空租户」的解释必须是同一个。
        val tenantKey = TenantKeys.canonical(input.tenantKey).ifBlank { TenantKeys.DEFAULT }
        // BCrypt 明确在事务外执行；后续数据库写入由一个短事务整体提交。
        val passwordHash = passwords.encode(input.password)
        return transactions.execute {
            val actor = actorId()
            val activeRoles = authGrants.lockRoleMutationFence()
            require(activeRoles.containsAll(roles)) { "角色不存在或已停用" }
            // 租户存在性在事务内校验（而不是靠外键报错）：外键失败会被上层统一映射成持久化异常，
            // 调用方就无法区分「租户填错了」和「库出问题了」。放在事务内也保证校验与插入同快照。
            require(tenants.activeKeys().contains(tenantKey)) { "租户不存在或已停用" }
            val id = authUsers.createLocal(
                AuthUserPo(
                    username = input.username,
                    displayName = input.displayName?.trim(),
                    passwordHash = passwordHash,
                    authProvider = AuthProvider.LOCAL.code,
                    tenantKey = tenantKey,
                ),
            )
            authGrants.replace(id, roles, actor)
            adminScopes.replace(id, scopes)
            audit(actor, AuthorizationAuditAction.AUTH_USER_CREATED, AuthorizationAuditTarget.AUTH_USER, id.toString(), mapOf(
                "roles" to roles.map(RoleCode::code).sorted(),
                "scopeCount" to scopes.size,
                "tenantKey" to tenantKey,
            ))
            mapOf("id" to id)
        } ?: error("AUTH_USER_CREATE_TRANSACTION_EMPTY")
    }

    /** 更新用户资料或启用状态并写审计；停用时先锁角色栅栏、再锁用户，保护最后管理员。 */
    @Transactional
    override fun updateUser(id: Long, input: UserUpdateInput): Map<String, Any> {
        require(input.displayName == null || input.displayName.length <= 100)
        val user = if (!input.active) {
            authGrants.lockRoleMutationFence()
            lockUser(id)
        } else {
            lockUser(id)
        }
        if (!input.active) {
            validateCanDisable(user, authGrants.roles(id))
        }
        val n = authUsers.updateAdminProfile(id, input.displayName?.trim(), input.active)
        if (n == 0) {
            throw NoSuchElementException("USER_NOT_FOUND")
        }
        audit(actorId(), AuthorizationAuditAction.AUTH_USER_UPDATED, AuthorizationAuditTarget.AUTH_USER, id.toString(), mapOf("active" to input.active))
        return mapOf("id" to id)
    }

    /** 停用指定账号并写审计；重复停用保持幂等，账号不存在和管理员保护则失败关闭。 */
    @Transactional
    override fun disableUser(id: Long): Map<String, Any> {
        authGrants.lockRoleMutationFence()
        val user = lockUser(id)
        validateCanDisable(user, authGrants.roles(id))
        authUsers.disable(id)
        audit(actorId(), AuthorizationAuditAction.AUTH_USER_DISABLED, AuthorizationAuditTarget.AUTH_USER, id.toString(), emptyMap())
        return mapOf("disabled" to true)
    }

    /** 用户角色替换、authVersion 提升与安全审计属于一个本地一致性阶段。 */
    @Transactional
    override fun assignRoles(id: Long, assigned: Set<String>): Map<String, Any> {
        val roles = parseRoles(assigned)
        val activeRoles = authGrants.lockRoleMutationFence()
        require(activeRoles.containsAll(roles)) { "角色不存在或已停用" }
        val user = lockUser(id)
        val previous = authGrants.roles(id)
        if (roles.any(feedbackRoles::contains)) {
            require(isBoundFeishuUser(user)) {
                "反馈权限只能分配给已完成飞书登录绑定的启用账号"
            }
        }
        if (user.active && RoleCode.ADMIN in previous && RoleCode.ADMIN !in roles) {
            require(activeAdminCount() > 1) {
                "不能移除唯一有效管理员的 ADMIN 角色"
            }
        }
        val actor = actorId()
        authGrants.replace(id, roles, actor)
        authUsers.invalidateSessions(id)
        audit(actor, AuthorizationAuditAction.USER_ROLES_ASSIGNED, AuthorizationAuditTarget.AUTH_USER, id.toString(), mapOf(
            "previousRoles" to previous.map(RoleCode::code).sorted(),
            "assignedRoles" to roles.map(RoleCode::code).sorted(),
        ))
        return mapOf("roles" to roles.map(RoleCode::code).sorted())
    }

    /** 数据范围替换、authVersion 提升和审计共同提交，避免新范围生效后旧会话继续存活。 */
    @Transactional
    override fun replaceDataPermissions(id: Long, scopes: List<DataScope>): Map<String, Any> {
        require(scopes.size <= 100)
        val normalized = scopes.map(::normalizeScope)
        val actor = actorId()
        lockUser(id)
        adminScopes.replace(id, normalized)
        authUsers.invalidateSessions(id)
        audit(actor, AuthorizationAuditAction.USER_DATA_PERMISSIONS_REPLACED, AuthorizationAuditTarget.AUTH_USER, id.toString(), mapOf(
            "scopeCount" to normalized.size,
        ))
        return mapOf("scopes" to normalized.size)
    }

    /** 获取当前授权操作者；无安全主体时拒绝管理写入。 */
    private fun actorId() = (SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId
        ?: throw IllegalStateException("未认证")

    /** 锁定并返回目标用户当前行；不存在时统一映射为管理接口的用户不存在语义。 */
    private fun lockUser(id: Long): AuthUserPo = authUsers.lockById(id)
        ?: throw NoSuchElementException("USER_NOT_FOUND")

    /** 只有启用的 SSO 账号且存在飞书身份绑定时才满足反馈角色分配前置条件。 */
    private fun isBoundFeishuUser(user: AuthUserPo) =
        AuthProvider.fromStorage(user.authProvider) == AuthProvider.SSO && user.active &&
            externalIdentities.feishuByUserIds(setOf(checkNotNull(user.id))).isNotEmpty()

    /** 在 ADMIN 角色栅栏锁内使用数据库聚合计数，避免无界用户 ID 扫描和并发写偏差。 */
    private fun activeAdminCount() = authGrants.activeUserCount(RoleCode.ADMIN)

    /** 校验停用不变量；调用前必须依次持有角色栅栏和目标用户行锁。 */
    private fun validateCanDisable(user: AuthUserPo, roles: Set<RoleCode>) {
        val id = checkNotNull(user.id)
        require(id != actorId()) { "不能停用当前登录用户" }
        if (user.active && RoleCode.ADMIN in roles) {
            require(activeAdminCount() > 1) {
                "不能停用唯一有效管理员"
            }
        }
    }

    /** 将 HTTP 角色码解析为封闭领域集合，并校验反馈专用角色必须独占。 */
    private fun parseRoles(values: Set<String>): Set<RoleCode> {
        val parsed = values.mapTo(linkedSetOf()) { requireAssignableRole(it) }
        require(parsed.isNotEmpty()) { "角色非法" }
        require(parsed.none(feedbackRoles::contains) || (parsed.size == 1 && parsed.first() in feedbackRoles)) {
            "反馈技术/产品角色必须独占且不可同时分配"
        }
        return parsed
    }

    /** 解析并限制为平台允许人工分配的角色；未知或内部角色一律拒绝。 */
    private fun requireAssignableRole(value: String): RoleCode {
        val role = RoleCode.fromBoundary(value)
        require(role != null && role in assignableRoles) { "角色非法" }
        return role
    }

    /**
     * 将数据范围边界值转换为强类型写命令，并校验渠道目录、字段格式和访问级别不变量。
     */
    private fun normalizeScope(scope: DataScope): AuthDataScopeCommand {
        val type = DataScopeType.fromBoundary(scope.type)
        val accessLevel = DataAccessLevel.fromBoundary(scope.accessLevel)
        val effect = DataPermissionEffect.fromBoundary(scope.effect)
        require(type != null && accessLevel != null && effect != null)
        val value = scope.value.trim().uppercase()
        require(value.isNotBlank() && value.length <= 100)
        if (type == DataScopeType.CHANNEL) {
            require(value == DataScopeValues.ALL || value in com.feedback.common.FeedbackChannel.feedbackWireValues)
        }
        if (type == DataScopeType.FIELD) {
            require(accessLevel == DataAccessLevel.READ)
            require(value.matches(Regex("^(\\*|[A-Za-z][A-Za-z0-9_]{0,99})$")))
        }
        return AuthDataScopeCommand(type, value, accessLevel, effect)
    }

    /** 追加安全审计；仅由短写事务调用，确保管理变更、会话失效和审计原子提交。 */
    private fun audit(
        actor: Long,
        operation: AuthorizationAuditAction,
        targetType: AuthorizationAuditTarget,
        targetId: String,
        detail: Map<String, Any?>,
    ) {
        auditMutations.append(
            SystemAuditInsertPo(
                actor,
                operation.code,
                AuthorizationAuditClass.SECURITY.code,
                AuthorizationAuditOutcome.SUCCESS.code,
                targetType.code,
                targetId,
                mapper.writeValueAsString(detail),
            ),
        )
    }

    /** 统一构造保持既有 HTTP 编码不变的分页响应。 */
    private fun page(items: List<Map<String, Any?>>, total: Long, page: Int, size: Int) =
        mapOf("items" to items, "total" to total, "page" to page, "size" to size)
}
