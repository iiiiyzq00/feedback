package com.feedback.service.impl.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.AuthorizationAuditAction
import com.feedback.model.auth.AuthorizationAuditClass
import com.feedback.model.auth.AuthorizationAuditOutcome
import com.feedback.model.auth.AuthorizationAuditTarget
import com.feedback.model.auth.DataScopeValues
import com.feedback.model.auth.FeishuGrantKind
import com.feedback.model.auth.FeishuMappingDecision
import com.feedback.model.auth.FeishuRuleType
import com.feedback.model.auth.RoleCode
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.model.po.auth.AuthFeishuRoleMappingPo
import com.feedback.repository.auth.AuthRoleCatalogRepository
import com.feedback.repository.auth.FeishuRoleMappingRepository
import com.feedback.repository.auth.SystemAuditMutationRepository
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.FeishuRoleMappingDecisionInput
import com.feedback.model.auth.FeishuRoleMappingInput
import com.feedback.service.auth.FeishuRoleMappingService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 飞书角色/数据范围映射管理实现。
 *
 * 新建、版本 CAS 更新或审批状态迁移与安全审计均处于同一短事务；CAS 失败不写审计。
 * 本服务不访问飞书 HTTP，规则仅在后续显式同步中影响用户授权。
 */
@Service
class FeishuRoleMappingServiceImpl(
    private val mappings: FeishuRoleMappingRepository,
    private val rolesCatalog: AuthRoleCatalogRepository,
    private val audits: SystemAuditMutationRepository,
    private val mapper: ObjectMapper,
) : FeishuRoleMappingService {
    private val assignableRoles = setOf(
        RoleCode.ADMIN,
        RoleCode.OPERATOR,
        RoleCode.AUDITOR,
        RoleCode.USER,
        RoleCode.PHASE_ONE_TECHNICAL,
        RoleCode.PHASE_ONE_PRODUCT,
    )

    /** 在一个短只读事务中返回映射、角色目录和稳定的规则优先级编码。 */
    @Transactional(readOnly = true)
    override fun list(): Map<String, Any> {
        val items = mappings.all()
        return mapOf(
            "items" to items,
            "roles" to rolesCatalog.active(),
            "runtimeMode" to "DECISION_ONLY",
            "precedence" to FeishuRuleType.entries.sortedBy(FeishuRuleType::precedence).map(FeishuRuleType::code),
            "approvedCount" to items.count {
                FeishuMappingDecision.requireStorage(it.decisionStatus) == FeishuMappingDecision.APPROVED
            },
        )
    }

    /** 创建已规范化的草稿并同步写入安全审计；任一步失败时整体回滚。 */
    @Transactional
    override fun create(input: FeishuRoleMappingInput): Map<String, Any> {
        val actor = actorId()
        val value = normalize(input, createdBy = actor)
        val id = mappings.create(value)
        audit(actor, AuthorizationAuditAction.FEISHU_ROLE_MAPPING_CREATED, id, mapOf(
            "status" to FeishuMappingDecision.DRAFT.code,
            "ruleType" to value.ruleType,
            "roleCode" to value.roleCode,
        ))
        return mapOf("id" to id, "decisionStatus" to FeishuMappingDecision.DRAFT.code, "version" to 1)
    }

    /** 仅以 expectedVersion 更新草稿；CAS 冲突失败关闭且不得留下成功审计。 */
    @Transactional
    override fun update(id: Long, input: FeishuRoleMappingInput): Map<String, Any> {
        val version = input.expectedVersion ?: invalid("FEISHU_MAPPING_VERSION_REQUIRED")
        val actor = actorId()
        val value = normalize(input, id = id, version = version, createdBy = actor)
        if (mappings.updateDraft(value) != 1) {
            invalid("FEISHU_MAPPING_NOT_DRAFT_OR_VERSION_CONFLICT")
        }
        audit(actor, AuthorizationAuditAction.FEISHU_ROLE_MAPPING_UPDATED, id, mapOf(
            "version" to version + 1,
            "roleCode" to value.roleCode,
        ))
        return mapOf("id" to id, "decisionStatus" to FeishuMappingDecision.DRAFT.code, "version" to version + 1)
    }

    /**
     * 按允许的状态机审批、拒绝或退役映射，并以版本 CAS 防止覆盖并发决策。
     * 状态变更与审计共用短写事务。
     */
    @Transactional
    override fun decide(id: Long, input: FeishuRoleMappingDecisionInput): Map<String, Any> {
        val decision = FeishuMappingDecision.fromBoundary(input.status)
            ?.takeIf { it != FeishuMappingDecision.DRAFT }
            ?: invalid("FEISHU_MAPPING_DECISION_INVALID")
        val reason = input.reason.trim()
        if (reason.length !in 5..500) {
            invalid("FEISHU_MAPPING_DECISION_REASON_REQUIRED")
        }
        val current = if (decision == FeishuMappingDecision.RETIRED) {
            FeishuMappingDecision.APPROVED
        } else {
            FeishuMappingDecision.DRAFT
        }
        val actor = actorId()
        if (mappings.decide(id, decision.code, reason, actor, input.expectedVersion, current.code) != 1) {
            invalid("FEISHU_MAPPING_DECISION_CONFLICT")
        }
        audit(actor, AuthorizationAuditAction.forDecision(decision), id, mapOf(
            "reason" to reason,
            "version" to input.expectedVersion + 1,
        ))
        return mapOf("id" to id, "decisionStatus" to decision.code, "version" to input.expectedVersion + 1)
    }

    /**
     * 将 HTTP 边界字符串解析为稳定领域编码并校验规则不变量；只在返回 PO 时转回存储 code。
     */
    private fun normalize(
        input: FeishuRoleMappingInput,
        id: Long? = null,
        version: Long = 1,
        createdBy: Long,
    ): AuthFeishuRoleMappingPo {
        val type = FeishuRuleType.fromBoundary(input.ruleType) ?: invalid("FEISHU_MAPPING_RULE_TYPE_INVALID")
        val grantKind = FeishuGrantKind.fromBoundary(input.grantKind) ?: invalid("FEISHU_MAPPING_GRANT_KIND_INVALID")
        val role = RoleCode.fromBoundary(input.roleCode)
        if (grantKind == FeishuGrantKind.ROLE && role !in assignableRoles) {
            invalid("FEISHU_MAPPING_ROLE_INVALID")
        }
        val scopeType = input.scopeType?.let(DataScopeType::fromBoundary)
        val scopeValue = input.scopeValue?.trim()
        if (grantKind == FeishuGrantKind.DATA_SCOPE) {
            if (type !in setOf(FeishuRuleType.USER_GROUP, FeishuRuleType.DEPARTMENT)) {
                invalid("FEISHU_SCOPE_POLICY_SUBJECT_INVALID")
            }
            if (scopeType !in setOf(DataScopeType.CHANNEL, DataScopeType.MODULE, DataScopeType.REGION)) {
                invalid("FEISHU_SCOPE_POLICY_TYPE_INVALID")
            }
            if (scopeValue.isNullOrBlank() || scopeValue.length > 100) {
                invalid("FEISHU_SCOPE_POLICY_VALUE_INVALID")
            }
        }
        val subjectId = if (type == FeishuRuleType.DEFAULT) DataScopeValues.ALL else input.subjectId.trim()
        if (subjectId.isBlank() || subjectId.length > 128) {
            invalid("FEISHU_MAPPING_SUBJECT_ID_INVALID")
        }
        val subjectName = input.subjectName.trim()
        if (subjectName.isBlank() || subjectName.length > 100) {
            invalid("FEISHU_MAPPING_SUBJECT_NAME_INVALID")
        }
        if (input.priority !in 1..1000) {
            invalid("FEISHU_MAPPING_PRIORITY_INVALID")
        }
        val description = input.description?.trim()?.takeIf(String::isNotBlank)
        if (description != null && description.length > 500) {
            invalid("FEISHU_MAPPING_DESCRIPTION_INVALID")
        }
        return AuthFeishuRoleMappingPo(
            id = id,
            ruleType = type.code,
            subjectId = subjectId,
            subjectName = subjectName,
            grantKind = grantKind.code,
            roleCode = role?.code.takeIf { grantKind == FeishuGrantKind.ROLE },
            scopeType = scopeType?.code.takeIf { grantKind == FeishuGrantKind.DATA_SCOPE },
            scopeValue = scopeValue.takeIf { grantKind == FeishuGrantKind.DATA_SCOPE },
            priority = input.priority,
            description = description,
            version = version,
            createdBy = createdBy,
        )
    }

    /** 获取当前审计操作者；缺失主体时失败关闭，禁止产生无归属授权变更。 */
    private fun actorId() = (SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId
        ?: throw ClientException(ErrorCode.AUTH_UNAUTHORIZED, "AUTHENTICATION_REQUIRED")

    /** 将边界校验失败统一映射为稳定的客户端错误原因码。 */
    private fun invalid(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

    /** 追加权限安全审计；调用者事务保证授权动作与审计共同成功或回滚。 */
    private fun audit(actor: Long, operation: AuthorizationAuditAction, id: Long, detail: Map<String, Any?>) {
        audits.append(
            SystemAuditInsertPo(
                userId = actor,
                operation = operation.code,
                auditClass = AuthorizationAuditClass.SECURITY.code,
                outcome = AuthorizationAuditOutcome.SUCCESS.code,
                targetType = AuthorizationAuditTarget.FEISHU_ROLE_MAPPING.code,
                targetId = id.toString(),
                operationDetailJson = mapper.writeValueAsString(detail),
            ),
        )
    }
}
