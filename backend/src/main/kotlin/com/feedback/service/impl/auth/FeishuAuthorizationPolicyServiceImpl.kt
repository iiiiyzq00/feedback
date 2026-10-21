package com.feedback.service.impl.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.AbstractException
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.config.FeishuSsoProperties
import com.feedback.model.po.auth.AuthFeishuRoleMappingPo
import com.feedback.repository.auth.AuthExternalIdentityRepository
import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.repository.auth.FeishuPolicyRepository
import com.feedback.repository.auth.FeishuRoleMappingRepository
import com.feedback.repository.auth.FeishuPolicyScope
import com.feedback.repository.auth.SystemAuditMutationRepository
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.FeishuGrantKind
import com.feedback.model.auth.FeishuRuleType
import com.feedback.model.auth.FeishuSyncStatus
import com.feedback.model.auth.AuthorizationAuditAction
import com.feedback.model.auth.AuthorizationAuditClass
import com.feedback.model.auth.AuthorizationAuditOutcome
import com.feedback.model.auth.AuthorizationAuditTarget
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.service.auth.FeishuAuthorizationPolicyService
import com.feedback.repository.remote.auth.FeishuOrganizationClient
import com.feedback.model.auth.FeishuOrganizationFacts
import com.feedback.model.auth.FeishuPolicyPreviewInput
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.support.TransactionOperations
import java.time.LocalDateTime

/**
 * 飞书组织事实到本地角色/数据范围的编排服务实现。
 *
 * 每次远程查询都在事务外执行；单个用户的授权变更在独立短事务中提交，确保一个用户失败
 * 不会回滚其他用户，也不会在等待飞书响应时占用数据库连接和锁。
 */
@Service
class FeishuAuthorizationPolicyServiceImpl(
    private val policies: FeishuPolicyRepository,
    private val mappings: FeishuRoleMappingRepository,
    private val identities: AuthExternalIdentityRepository,
    private val users: AuthUserRepository,
    private val grants: AuthGrantRepository,
    private val audits: SystemAuditMutationRepository,
    private val objectMapper: ObjectMapper,
    private val properties: FeishuSsoProperties,
    private val organization: FeishuOrganizationClient,
    private val transactions: TransactionOperations
) : FeishuAuthorizationPolicyService {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 返回同步开关、最近运行和所需飞书能力；只读取本地状态，不触发远程 I/O。 */
    override fun status(): Map<String, Any?> = mapOf(
        "enabled" to properties.organizationSyncEnabled,
        "lastRun" to policies.latestRun(),
        "requiredCapabilities" to listOf("读取用户基本信息与任职状态", "读取用户所属部门", "读取用户所属用户组")
    )

    /** 使用调用方提供的组织事实预演规则，不写数据库、不调用飞书且不改变授权。 */
    override fun preview(input: FeishuPolicyPreviewInput) = evaluate(
        input.openId,
        input.groupIds,
        input.departmentIds,
        mappings.approved(),
    )

    /**
     * 编排全量授权同步：飞书 HTTP 始终在事务外，单用户授权以独立短事务提交。
     * 启动互斥、瞬态数据库错误重试和最终运行状态均有明确上限及失败记录。
     */
    override fun syncAll(): Map<String, Any> {
        properties.validateOrganizationEnabled()
        val actor = actorId()
        val runId = transactions.execute {
            policies.startExclusive(
                actor,
                LocalDateTime.now().minusMinutes(properties.organizationSyncStaleMinutes)
            )
        } ?: serviceFailure("FEISHU_SYNC_START_TRANSACTION_EMPTY")
        val (identityRows, approvedRules) = try {
            retryTransient("load-authorization-snapshot") { identities.allFeishu() to mappings.approved() }
        } catch (error: Exception) {
            finishFailedRun(runId, error)
            throw error
        }
        var changed = 0
        var disabled = 0
        var failed = 0
        var firstErrorCode: String? = null

        identityRows.forEach { row ->
            val userId = row.userId
            val openId = row.providerSubject
            try {
                val facts = organization.facts(openId)
                // 规则计算和可能较大的组织事实 JSON 均在事务外完成；写阶段重新锁定角色与用户快照。
                val evaluation = evaluate(openId, facts.groupIds, facts.departmentIds, approvedRules)
                val desiredRole = RoleCode.requireStorage(evaluation["roleCode"].toString())
                val desiredScopes = (evaluation["dataScopes"] as List<*>).map { it as Map<*, *> }.mapTo(linkedSetOf()) {
                    FeishuPolicyScope(
                        DataScopeType.requireStorage(it["scopeType"].toString()),
                        it["scopeValue"].toString(),
                    )
                }
                val groupsJson = objectMapper.writeValueAsString(facts.groupIds.sorted())
                val departmentsJson = objectMapper.writeValueAsString(facts.departmentIds.sorted())
                val result = retryTransient("apply-user") {
                    transactions.execute {
                        apply(userId, openId, facts.active, desiredRole, desiredScopes, groupsJson, departmentsJson, actor)
                    }
                        ?: serviceFailure("FEISHU_SYNC_USER_TRANSACTION_EMPTY")
                }
                if (result.first) {
                    changed++
                }
                if (result.second) {
                    disabled++
                }
            } catch (error: Exception) {
                failed++
                val errorCode = (error as? AbstractException)?.errorCode?.name ?: ErrorCode.INTERNAL_ERROR.name
                if (firstErrorCode == null) {
                    firstErrorCode = errorCode
                }
                log.warn("Feishu organization user sync failed: userId={}, errorCode={}", userId, errorCode)
                runCatching {
                    retryTransient("mark-user-failure") {
                        transactions.execute { policies.markFailure(userId, errorCode) }
                            ?: serviceFailure("FEISHU_SYNC_FAILURE_MARK_TRANSACTION_EMPTY")
                    }
                }
                    .onFailure {
                        log.error(
                            "Feishu organization failure marker could not be persisted: userId={}, errorCode={}",
                            userId,
                            ErrorCode.INTERNAL_ERROR.name
                        )
                    }
            }
        }

        val status = if (failed == 0) FeishuSyncStatus.COMPLETED else FeishuSyncStatus.PARTIAL_SUCCESS
        retryTransient("finish-run") {
            transactions.execute {
                policies.finish(runId, status, identityRows.size, changed, disabled, failed, firstErrorCode)
            } ?: serviceFailure("FEISHU_SYNC_FINISH_TRANSACTION_EMPTY")
        }
        return mapOf(
            "runId" to runId,
            "status" to status.code,
            "totalUsers" to identityRows.size,
            "changedUsers" to changed,
            "disabledUsers" to disabled,
            "failedUsers" to failed
        )
    }

    /**
     * 在单用户短写事务内比较并替换角色、数据范围和启用状态；发生变化时同步提升
     * authVersion 并写审计，保证新权限生效与旧会话失效原子提交。
     */
    private fun apply(
        userId: Long,
        openId: String,
        active: Boolean,
        desiredRole: RoleCode,
        nextScopes: Set<FeishuPolicyScope>,
        groupsJson: String,
        departmentsJson: String,
        actor: Long,
    ): Pair<Boolean, Boolean> {
        val activeRoles = grants.lockRoleMutationFence()
        if (desiredRole !in activeRoles) {
            serviceFailure("FEISHU_SYNC_DESIRED_ROLE_INACTIVE")
        }
        val user = users.lockById(userId) ?: serviceFailure("FEISHU_SYNC_USER_NOT_FOUND")
        val currentRoles = grants.roles(userId)
        val currentScopes = policies.scopes(userId)
        val changed = currentRoles != setOf(desiredRole) || currentScopes != nextScopes ||
            user.active != active

        if (changed) {
            grants.replace(userId, desiredRole)
            policies.replaceScopes(userId, nextScopes)
            users.updateSsoActiveAndVersion(userId, active)
            audits.append(
                SystemAuditInsertPo(
                    userId = actor,
                    operation = AuthorizationAuditAction.FEISHU_USER_AUTHORIZATION_SYNCHRONIZED.code,
                    auditClass = AuthorizationAuditClass.SECURITY.code,
                    outcome = AuthorizationAuditOutcome.SUCCESS.code,
                    targetType = AuthorizationAuditTarget.AUTH_USER.code,
                    targetId = userId.toString(),
                    operationDetailJson = objectMapper.writeValueAsString(
                        mapOf(
                            "roleCode" to desiredRole.code,
                            "dataScopeCount" to nextScopes.size,
                            "active" to active,
                            "providerSubject" to openId,
                        ),
                    ),
                ),
            )
        }
        policies.saveFacts(
            userId,
            groupsJson,
            departmentsJson,
            active
        )
        return changed to !active
    }

    /** 按用户、用户组、部门、默认规则的稳定优先级计算目标角色与数据范围，不产生 I/O。 */
    private fun evaluate(
        openId: String?,
        groups: Set<String>,
        departments: Set<String>,
        rules: List<AuthFeishuRoleMappingPo>,
    ): Map<String, Any?> {
        /** 判断单条已审批规则是否匹配当前组织事实；未知存储编码直接失败关闭。 */
        fun matches(rule: AuthFeishuRoleMappingPo) = when (FeishuRuleType.requireStorage(rule.ruleType)) {
            FeishuRuleType.USER -> openId != null && rule.subjectId == openId
            FeishuRuleType.USER_GROUP -> rule.subjectId in groups
            FeishuRuleType.DEPARTMENT -> rule.subjectId in departments
            FeishuRuleType.DEFAULT -> true
        }
        val matched = rules.filter(::matches)
        val roleRule = matched.filter { FeishuGrantKind.requireStorage(it.grantKind) == FeishuGrantKind.ROLE }
            .minWithOrNull(compareBy<AuthFeishuRoleMappingPo> { FeishuRuleType.requireStorage(it.ruleType).precedence }
                .thenBy { it.priority })
        val scopes = matched.filter {
            FeishuGrantKind.requireStorage(it.grantKind) == FeishuGrantKind.DATA_SCOPE
        }.map {
            mapOf(
                "scopeType" to DataScopeType.requireStorage(checkNotNull(it.scopeType)).code,
                "scopeValue" to it.scopeValue,
                "ruleId" to it.id,
                "subjectName" to it.subjectName
            )
        }.distinctBy { it["scopeType"].toString() + ":" + it["scopeValue"] }
        return mapOf(
            "roleCode" to (roleRule?.roleCode ?: RoleCode.USER.code),
            "roleRule" to roleRule,
            "dataScopes" to scopes,
            "matchedRuleCount" to matched.size
        )
    }

    /** 获取同步发起人；未认证调用不允许启动授权同步。 */
    private fun actorId() =
        (SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId
            ?: throw ClientException(ErrorCode.AUTH_UNAUTHORIZED,"AUTHENTICATION_REQUIRED")

    /** 尝试在独立短事务中终结失败运行；记录失败本身失败时仅告警且保留原异常。 */
    private fun finishFailedRun(runId: Long, error: Exception) {
        val errorCode = (error as? AbstractException)?.errorCode?.name ?: ErrorCode.INTERNAL_ERROR.name
        runCatching {
            retryTransient("fail-run") {
                transactions.execute {
                    policies.finish(runId, FeishuSyncStatus.FAILED, 0, 0, 0, 0, errorCode)
                } ?: serviceFailure("FEISHU_SYNC_FAILED_RUN_TRANSACTION_EMPTY")
            }
        }.onFailure {
            log.error("Feishu organization failed run could not be finalized: runId={}", runId, it)
        }
    }

    /** 只重试瞬态数据库异常，次数由配置限定；业务异常和最后一次冲突直接向上传播。 */
    private fun <T> retryTransient(operation: String, action: () -> T): T {
        repeat(properties.organizationSyncRetryAttempts) { attempt ->
            try {
                return action()
            } catch (error: RuntimeException) {
                if (!hasTransientDataAccessCause(error) || attempt == properties.organizationSyncRetryAttempts - 1) {
                    throw error
                }
                log.warn("Feishu organization database contention retry: operation={}, attempt={}", operation, attempt + 1)
                pauseBeforeRetry(attempt + 1)
            }
        }
        serviceFailure("FEISHU_SYNC_RETRY_EXHAUSTED")
    }

    /** 按尝试次数线性退避；线程中断立即恢复中断标记并终止同步。 */
    private fun pauseBeforeRetry(attempt: Int) {
        val delay = properties.organizationSyncRetryDelayMs * attempt
        if (delay == 0L) {
            return
        }
        try {
            Thread.sleep(delay)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "FEISHU_SYNC_RETRY_INTERRUPTED", cause = error)
        }
    }

    /** 遍历异常因果链识别 Spring 瞬态数据访问错误，避免误重试业务失败。 */
    private fun hasTransientDataAccessCause(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is TransientDataAccessException }

    /** 将无法继续的内部编排状态转换为稳定服务错误。 */
    private fun serviceFailure(reasonCode:String):Nothing =
        throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
}
