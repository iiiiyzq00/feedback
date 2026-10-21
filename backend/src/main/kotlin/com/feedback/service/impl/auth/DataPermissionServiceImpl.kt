package com.feedback.service.impl.auth

import com.feedback.common.FeedbackChannel
import com.feedback.model.auth.DataPermissionEffect
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.DataScopeValues
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.TenantKeys
import com.feedback.repository.auth.AuthDataPermissionRepository
import com.feedback.repository.auth.AuthDataPermission
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.service.auth.DataPermissionService
import com.feedback.model.auth.DataPermissionSnapshot
import com.feedback.security.WebPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * 数据权限计算服务实现。
 *
 * Repository 通过一条有界 SQL 读取用户与角色授权，无 N+1；单条查询使用自动提交，返回后在
 * 事务外完成范围合并，避免权限热路径占用事务连接。未知数据库编码由 Repository 失败关闭。
 */
@Service
class DataPermissionServiceImpl(
    private val permissions: AuthDataPermissionRepository,
) : DataPermissionService {
    private val allChannels = FeedbackChannel.feedbackWireValues

    /** 返回当前主体可读渠道；未认证主体按空集合失败关闭。 */
    override fun allowedChannels(): Set<String> = scope().channels

    /** 从安全上下文读取主体并计算完整数据范围；缺失主体时不授予任何范围。 */
    override fun scope(): DataAccessScope {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
            ?: return DataAccessScope(emptySet(), emptySet(), emptySet(), emptySet())
        return scopeFor(principal.userId, principal.roles, principal.tenantKey)
    }

    /**
     * 用一条有界查询合并指定用户与角色的数据范围。
     *
     * 管理员是唯一的内建全范围旁路，且**租户维度一并旁路**（`tenants = null` ⇒ 不受限）。
     * 这是本平台唯一一处「不显式授权即可跨租户」的路径，理由是平台运维本来就要跨租户排障；
     * 若某天要收掉它，改动点就是这一行，而不是散落各处的判定。
     *
     * 查询为单条原子 SQL，不额外开启事务；未知编码由仓储拒绝而非降级放行。
     */
    override fun scopeFor(userId: Long, roles: Set<RoleCode>, tenantKey: String): DataAccessScope {
        if (RoleCode.ADMIN in roles) {
            return DataAccessScope(allChannels, null, null, null)
        }
        val rows = permissions.findReadPermissions(userId, roles)
        return scopeFrom(rows, tenantKey)
    }

    /** 按范围类型合并 ALLOW/DENY，DENY 始终优先，无法安全表达的通配组合返回空范围。 */
    private fun scopeFrom(rows: List<AuthDataPermission>, ownTenant: String): DataAccessScope {
        /** 计算单一范围维度；null 表示该维度无限制，空集合表示禁止访问。 */
        fun values(type: DataScopeType, universe: Set<String>? = null): Set<String>? {
            val typed = rows.filter { it.scopeType == type }
            if (typed.isEmpty()) {
                return if (type == DataScopeType.CHANNEL) emptySet() else null
            }
            val allowRows = typed.filter { it.effect == DataPermissionEffect.ALLOW }
                .map { it.scopeValue.uppercase() }
            val denyRows = typed.filter { it.effect == DataPermissionEffect.DENY }
                .map { it.scopeValue.uppercase() }
            if (DataScopeValues.ALL in denyRows) {
                return emptySet()
            }
            // MODULE/REGION wildcard + exclusions cannot由当前表结构安全表达，必须失败关闭。
            if (DataScopeValues.ALL in allowRows && universe == null) {
                return if (denyRows.isEmpty()) null else emptySet()
            }
            val allowed = if (DataScopeValues.ALL in allowRows && universe != null) {
                universe.toMutableSet()
            } else {
                allowRows.filterNot { it == DataScopeValues.ALL }.toMutableSet()
            }
            denyRows.forEach(allowed::remove)
            return allowed
        }
        return DataAccessScope(
            values(DataScopeType.CHANNEL, allChannels).orEmpty(),
            values(DataScopeType.MODULE),
            values(DataScopeType.REGION),
            tenantValues(rows, ownTenant),
        )
    }

    /**
     * 合并租户维度。它与上面那三个维度**只有一处**语义差别：没有 TENANT 授权行时不是「拒绝全部」，
     * 而是「只看自己租户」。
     *
     * 为什么这里不能照抄渠道的默认：渠道授权是本平台的准入条件，没有渠道行确实该什么都看不到；
     * 而没有一个 TENANT 行的主体显然应该能看到自己租户的数据。若照抄，V245 上线当天全部存量用户
     * 会立刻看不到任何数据——那不是收紧，是事故。跨租户读取必须由显式 ALLOW 行授予，DENY 始终优先。
     *
     * 主体自身租户为空时返回空集合（拒绝）：一个无法归属到任何租户的主体，
     * 不能因为「行里的租户键也是空的」而被放行——那正好是空串对空串的漏洞。
     */
    private fun tenantValues(rows: List<AuthDataPermission>, ownTenant: String): Set<String>? {
        val own = TenantKeys.canonical(ownTenant)
        val typed = rows.filter { it.scopeType == DataScopeType.TENANT }
        if (typed.isEmpty()) {
            return if (own.isEmpty()) emptySet() else setOf(own)
        }
        val allowRows = typed.filter { it.effect == DataPermissionEffect.ALLOW }
            .map { TenantKeys.canonical(it.scopeValue) }
        val denyRows = typed.filter { it.effect == DataPermissionEffect.DENY }
            .map { TenantKeys.canonical(it.scopeValue) }
        if (DataScopeValues.ALL in denyRows) {
            return emptySet()
        }
        if (DataScopeValues.ALL in allowRows) {
            // 通配 ALLOW 表示「不限租户」。带具体 DENY 时无法用有限集合安全表达，必须失败关闭——
            // 与 MODULE/REGION 对通配的处理口径保持一致。
            return if (denyRows.isEmpty()) null else emptySet()
        }
        val allowed = allowRows.filterNot { it == DataScopeValues.ALL }.toMutableSet()
        allowed += own
        // 显式 DENY 连自己的租户也能去掉：那是「这个账号不该再看自己租户的数据」的显式表达。
        // 方向是收紧，因此不做「自己租户不可被 DENY」的特例。
        denyRows.forEach(allowed::remove)
        return allowed
    }

    /** 合并当前主体的字段可见性；未认证主体默认隐藏全部字段。 */
    override fun fieldPolicy(): DataFieldPolicy {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
            ?: return DataFieldPolicy(false, emptySet(), setOf(DataScopeValues.ALL))
        if (RoleCode.ADMIN in principal.roles) {
            return unrestrictedFields()
        }
        return fieldPolicyFrom(permissions.findReadPermissions(principal.userId, principal.roles))
    }

    /**
     * 用同一条有界查询结果同时计算行范围与字段策略，避免两次读取漂移；合并在查询返回后执行。
     */
    override fun snapshot(): DataPermissionSnapshot {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
            ?: return DataPermissionSnapshot(
                DataAccessScope(emptySet(), emptySet(), emptySet(), emptySet()),
                DataFieldPolicy(false, emptySet(), setOf(DataScopeValues.ALL)),
            )
        if (RoleCode.ADMIN in principal.roles) {
            return DataPermissionSnapshot(DataAccessScope(allChannels, null, null, null), unrestrictedFields())
        }
        // 范围与字段策略共享一条有界 SQL 结果，不产生第二次数据库读取。
        val rows = permissions.findReadPermissions(principal.userId, principal.roles)
        return DataPermissionSnapshot(scopeFrom(rows, principal.tenantKey), fieldPolicyFrom(rows))
    }

    /** 合并字段级 ALLOW/DENY；字段名先规范化，最终判定仍由 DENY 覆盖 ALLOW。 */
    private fun fieldPolicyFrom(allRows: List<AuthDataPermission>): DataFieldPolicy {
        val rows = allRows.filter { it.scopeType == DataScopeType.FIELD }
        if (rows.isEmpty()) {
            return DataFieldPolicy(true, emptySet(), emptySet())
        }
        val allowed = rows.filter { it.effect == DataPermissionEffect.ALLOW }
            .map { DataFieldPolicy.canonicalField(it.scopeValue) }
            .toSet()
        val denied = rows.filter { it.effect == DataPermissionEffect.DENY }
            .map { DataFieldPolicy.canonicalField(it.scopeValue) }
            .toSet()
        return DataFieldPolicy(DataScopeValues.ALL in allowed, allowed, denied)
    }

    /** 构造管理员字段无限制策略，集中表达通配语义。 */
    private fun unrestrictedFields() = DataFieldPolicy(true, emptySet(), emptySet())
}
