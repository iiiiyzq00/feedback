package com.feedback.repository.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.audit.SystemAuditMutationMapper
import com.feedback.mapper.auth.*
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.model.po.auth.*
import com.feedback.model.auth.DataAccessLevel
import com.feedback.model.auth.DataPermissionEffect
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.PermissionCode
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.FeishuSyncStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * 用户账号仓储：封装本地/SSO 用户查询、创建、资料刷新和 authVersion 会话失效操作。
 * 所有关键单行写入都会校验影响行数，避免登录流程在静默写入失败后继续执行。
 */
@Repository
class AuthUserRepository(private val mapper: AuthUserMapper) {
    /** 按用户名加载启用账号，供本地认证主体装配。 */
    fun activeByUsername(username: String) = mapper.findActiveByUsername(username)

    /** 按内部 ID 加载启用账号，供会话和 SSO 登录恢复。 */
    fun activeById(id: Long) = mapper.findActiveById(id)

    /** 读取包含停用状态的账号，供账号存在性和身份绑定判断。 */
    fun byUsername(username: String) = mapper.findByUsername(username)

    /** 在授权写事务中锁定用户当前行；调用方必须先获取角色变更栅栏以维持统一锁顺序。 */
    fun lockById(id: Long) = mapper.lockById(id)

    /** 执行有界管理分页查询；角色、范围由调用方按页内 ID 批量装配。 */
    fun page(keyword: String?, offset: Long, size: Int) = mapper.findPage(keyword, offset, size)

    /** 使用与分页相同的关键字口径统计账号总数。 */
    fun count(keyword: String?) = mapper.count(keyword)

    /** 按调用方已限定的 ID 集合批量读取账号，空集合直接返回。 */
    fun byIds(ids: Set<Long>) = if (ids.isEmpty()) emptyList() else mapper.findByIds(ids)

    /** 创建本地账号并严格校验影响行数及数据库生成主键。 */
    fun createLocal(user: AuthUserPo): Long {
        persisted(mapper.insertLocal(user) == 1, "AUTH_LOCAL_USER_INSERT_FAILED")
        return user.id ?: failed("AUTH_LOCAL_USER_ID_MISSING")
    }

    /** 创建 SSO 账号并严格校验影响行数及数据库生成主键。 */
    fun createSso(user: AuthUserPo): Long {
        persisted(mapper.insertSso(user) == 1, "AUTH_SSO_USER_INSERT_FAILED")
        return user.id ?: failed("AUTH_SSO_USER_ID_MISSING")
    }

    /** 单条 UPDATE 记录最近登录时间，未命中时失败关闭。 */
    fun touchLastLogin(id: Long) {
        persisted(mapper.touchLastLogin(id) == 1, "AUTH_LAST_LOGIN_UPDATE_FAILED")
    }

    /** 原子提升单用户 authVersion；未命中不能伪装成会话已经失效。 */
    fun invalidateSessions(id: Long) {
        persisted(mapper.incrementAuthVersion(id) == 1, "AUTH_VERSION_UPDATE_FAILED")
    }

    /** 权限包变更时按角色集合式提升全部关联用户版本，避免无界 ID 集合和巨大 IN SQL。 */
    fun invalidateSessionsByRole(roleCode: RoleCode): Int = mapper.incrementAuthVersionByRole(roleCode.code)

    /** 更新管理资料，影响行数交由服务区分不存在；启停变化由 SQL 同步提升版本。 */
    fun updateAdminProfile(id: Long, displayName: String?, active: Boolean) = mapper.updateAdminProfile(id, displayName, active)

    /** 幂等停用启用账号并提升版本，0 表示无需变化或不存在。 */
    fun disable(id: Long) = mapper.disableActive(id)

    /** 刷新 SSO 展示资料，写入失败触发当前身份绑定事务回滚。 */
    fun refreshSsoProfile(id: Long, displayName: String, avatarUrl: String?) {
        persisted(mapper.updateSsoProfile(id, displayName, avatarUrl) == 1, "AUTH_SSO_PROFILE_UPDATE_FAILED")
    }

    /** 飞书授权变化时同时更新启用状态与 authVersion，保证旧会话原子失效。 */
    fun updateSsoActiveAndVersion(id: Long, active: Boolean) {
        persisted(mapper.updateSsoActiveAndVersion(id, active) == 1, "AUTH_SSO_ACTIVE_UPDATE_FAILED")
    }
}

/** 用户角色与角色权限仓储：为登录主体装配有效授权，也支持受控的角色授予与替换。 */
interface AuthGrantRepository {
    /** 返回用户当前有效角色，未知存储编码必须失败关闭。 */
    fun activeRoles(userId: Long): Set<RoleCode>
    /** 返回用户完整角色集合，供授权管理和同步差异比较。 */
    fun roles(userId: Long): Set<RoleCode>
    /** 按页内用户集合批量读取角色，禁止逐用户 N+1。 */
    fun rolesByUsers(userIds: Set<Long>): Map<Long, List<RoleCode>>
    /** 统计启用且角色仍有效的用户数量；调用方持有角色变更栅栏时结果用于最后管理员保护。 */
    fun activeUserCount(roleCode: RoleCode): Long
    /**
     * 以稳定顺序锁定全部有效角色，序列化低频授权控制面写入。
     * 用户角色写入必须先调用本方法、再锁用户行，防止与权限包更新形成会话失效窗口。
     */
    fun lockRoleMutationFence(): Set<RoleCode>
    /** 一次批量展开角色权限，空角色集合不得产生授权。 */
    fun permissions(roleCodes: Set<RoleCode>): Set<PermissionCode>
    /** 插入单条角色授权；调用方负责事务、角色栅栏和 authVersion。 */
    fun grant(userId: Long, roleCode: RoleCode, assignedBy: Long? = null)
    /** 幂等授予首次 SSO 登录的最小角色。 */
    fun grantIfAbsent(userId: Long, roleCode: RoleCode)
    /** 单角色替换用于飞书策略同步；调用方事务必须同时覆盖 authVersion。 */
    fun replace(userId: Long, roleCode: RoleCode)
    /** 多角色替换用于管理员授权；调用方事务必须同时覆盖审计和 authVersion。 */
    fun replace(userId: Long, roleCodes: Set<RoleCode>, assignedBy: Long)
    /** 返回完整强类型角色权限矩阵供管理端展示。 */
    fun allRolePermissions(): List<RolePermissionGrant>
    /** 删除后重建角色权限；调用方事务必须同时集合式失效成员会话并写审计。 */
    fun replaceRolePermissions(roleCode: RoleCode, permissionCodes: Set<PermissionCode>, actor: Long)
}

/** 角色权限目录关联的强类型仓储结果。 */
data class RolePermissionGrant(val roleCode: RoleCode, val permissionCode: PermissionCode)

/** 飞书外部身份仓储：维护 provider subject 与本地 userId 的稳定绑定及资料刷新。 */
@Repository
class AuthExternalIdentityRepository(private val mapper: AuthExternalIdentityMapper) {
    /** 按飞书主体查找唯一绑定，本方法不创建事务。 */
    fun feishuBySubject(subject: String) = mapper.findFeishuBySubject(subject)

    /** 返回同步运行使用的全量飞书身份快照；远程调用必须在读取完成后进行。 */
    fun allFeishu() = mapper.findAllFeishu()

    /** 按页内用户批量读取飞书绑定，空集合直接返回。 */
    fun feishuByUserIds(userIds: Set<Long>) = if (userIds.isEmpty()) emptyList() else mapper.findFeishuByUserIds(userIds)

    /** 创建唯一身份绑定，冲突向上传播以防主体串号。 */
    fun create(identity: AuthExternalIdentityPo) {
        persisted(mapper.insert(identity) == 1, "AUTH_EXTERNAL_IDENTITY_INSERT_FAILED")
    }

    /** 刷新既有身份资料，禁止静默忽略未命中。 */
    fun refresh(identity: AuthExternalIdentityPo) {
        persisted(mapper.updateFeishu(identity) == 1, "AUTH_EXTERNAL_IDENTITY_UPDATE_FAILED")
    }
}

/** 飞书 OAuth state 和前端登录短码仓储：暴露加锁读取与条件消费原语。 */
@Repository
class AuthSsoRepository(private val states: AuthSsoStateMapper, private val codes: AuthSsoLoginCodeMapper) {
    /** 保存 OAuth state 摘要和可信回跳上下文，单条写入不额外开事务。 */
    fun createState(state: AuthSsoStatePo) {
        persisted(states.insert(state) == 1, "AUTH_SSO_STATE_INSERT_FAILED")
    }

    /** 在远程 OAuth 前无锁读取可用 state，不跨 HTTP 持有数据库事务。 */
    fun findUsableState(hash: ByteArray) = states.findUsable(hash)

    /** 在回调本地事务中锁定并复核 state，防止并发重复兑换。 */
    fun lockState(hash: ByteArray) = states.lockUsable(hash)

    /** 条件消费已锁定 state，影响行数由上层解释为并发冲突。 */
    fun consumeState(hash: ByteArray) = states.consume(hash)

    /** 保存一次性登录短码摘要，必须加入 state 消费事务。 */
    fun createLoginCode(code: AuthSsoLoginCodePo) {
        persisted(codes.insert(code) == 1, "AUTH_SSO_LOGIN_CODE_INSERT_FAILED")
    }

    /** 在登录兑换事务中锁定仍可用短码。 */
    fun lockLoginCode(hash: ByteArray) = codes.lockUsable(hash)

    /** 条件消费短码，0 表示并发兑换已抢先完成。 */
    fun consumeLoginCode(hash: ByteArray) = codes.consume(hash)
}

/** 角色与权限目录的只读/加锁访问边界，供管理流程校验授权目标。 */
interface AuthRoleCatalogRepository {
    /** 返回全部有效角色目录供管理视图和输入校验。 */
    fun active(): List<AuthRolePo>
    /** 权限包替换前锁定有效角色，避免与角色停用并发。 */
    fun lockActive(roleCode: RoleCode): AuthRolePo?
    /** 返回平台权限目录全量供角色矩阵展示。 */
    fun permissions(): List<AuthPermissionPo>
    /** 按已校验权限码批量读取目录项，空集合直接返回。 */
    fun permissions(codes: Set<PermissionCode>): List<AuthPermissionPo>
}

/** 管理员显式配置的数据范围仓储；整体替换必须由上层事务覆盖删除与重建。 */
interface AuthAdminDataPermissionRepository {
    /** 按页内用户 ID 批量读取管理员显式数据范围。 */
    fun byUserIds(ids: Set<Long>): Map<String, List<AuthAdminDataScopePo>>
    /** 删除后重建必须加入上层权限管理短事务，并与 authVersion、审计共同提交。 */
    fun replace(userId: Long, scopes: List<AuthDataScopeCommand>)
}

/** 管理员替换用户数据范围时使用的内部写入命令。 */
data class AuthDataScopeCommand(
    val type: DataScopeType,
    val value: String,
    val accessLevel: DataAccessLevel,
    val effect: DataPermissionEffect,
)

/** 飞书角色映射规则仓储，写操作通过版本条件实现乐观并发控制。 */
interface FeishuRoleMappingRepository {
    /** 返回全部映射供管理端展示。 */
    fun all(): List<AuthFeishuRoleMappingPo>
    /** 返回一次同步运行使用的已审批规则快照。 */
    fun approved(): List<AuthFeishuRoleMappingPo>
    /** 创建规范化草稿并返回数据库主键。 */
    fun create(mapping: AuthFeishuRoleMappingPo): Long
    /** 按草稿状态和版本 CAS 更新，影响行数原样返回。 */
    fun updateDraft(mapping: AuthFeishuRoleMappingPo): Int
    /** 通过期望版本与当前状态双条件执行单次状态迁移；0 表示并发冲突或非法前态。 */
    fun decide(id: Long, status: String, reason: String, actor: Long, version: Long, current: String): Int
}

/** 飞书同步数据范围的强类型仓储值。 */
data class FeishuPolicyScope(val type: DataScopeType, val value: String)

/** 飞书组织授权同步的复杂持久化接口；实现聚合运行记录、范围和组织事实三个 Mapper。 */
interface FeishuPolicyRepository {
    /** 返回最近同步运行供状态查询，不触发同步。 */
    fun latestRun(): AuthFeishuSyncRunPo?
    /** 仅供同一 RepositoryImpl 的启动锁流程及数据库契约测试；调用方必须提供事务。 */
    fun start(actor: Long): Long
    /** 获取启动互斥并创建新运行；新鲜运行存在时失败关闭，陈旧运行在同事务过期。 */
    fun startExclusive(actor: Long, staleBefore: LocalDateTime): Long
    /** 写入运行终态与汇总计数，影响行数异常必须回滚。 */
    fun finish(id: Long, status: FeishuSyncStatus, total: Int, changed: Int, disabled: Int, failed: Int, errorCode: String?)
    /** 返回指定用户由飞书策略生成的强类型数据范围。 */
    fun scopes(userId: Long): Set<FeishuPolicyScope>
    /** 删除后重建飞书策略范围；调用方负责单用户短写事务。 */
    fun replaceScopes(userId: Long, values: Set<FeishuPolicyScope>)
    /** 幂等保存已在事务外序列化的组织事实。 */
    fun saveFacts(userId: Long, groupsJson: String, departmentsJson: String, active: Boolean)
    /** 独立记录单用户同步失败，不覆盖最后成功事实。 */
    fun markFailure(userId: Long, errorCode: String)
}

/** 系统安全审计的单条 INSERT 边界，写入失败必须向业务调用方传播。 */
@Repository
class SystemAuditMutationRepository(private val mapper: SystemAuditMutationMapper) {
    /** 追加单条安全审计并校验影响行数；调用方事务决定是否与业务变更共同提交。 */
    fun append(audit: SystemAuditInsertPo) {
        persisted(mapper.insert(audit) == 1, "SYSTEM_AUDIT_INSERT_FAILED")
    }
}

/** 将 Mapper 影响行数断言统一转换为可观测的持久化异常。 */
private fun persisted(condition: Boolean, reasonCode: String) {
    if (!condition) {
        failed(reasonCode)
    }
}

/** 将仓储写入不变量失败统一转换为内部错误，触发当前事务回滚。 */
private fun failed(reasonCode: String): Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
