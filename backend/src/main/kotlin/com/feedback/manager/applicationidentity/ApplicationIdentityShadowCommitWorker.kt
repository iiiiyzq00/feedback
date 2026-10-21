package com.feedback.manager.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.service.applicationidentity.*

import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.applicationidentity.ApplicationAliasKey
import com.feedback.model.applicationidentity.ApplicationAliasType
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import com.feedback.model.applicationidentity.ApplicationIdentitySourceInput
import com.feedback.model.applicationidentity.ApplicationResolutionStatus
import com.feedback.model.applicationidentity.NewApplicationIdentity
import com.feedback.model.applicationidentity.PrimaryProblemClassificationStatus
import com.feedback.model.applicationidentity.PrimaryProblemFact
import com.feedback.repository.applicationidentity.PrimaryProblemFactRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 影子结果短写事务 Worker；只执行快照复核、批量持久化与必须共同成功的审计。 */
@Component
class ApplicationIdentityShadowCommitWorker(
    private val identities: ApplicationIdentityRepository,
    private val facts: PrimaryProblemFactRepository,
    private val audits: BusinessAuditService
) {
    /** 空批次仅追加一次审计；不创建 identity、alias 或影子事实。 */
    @Transactional
    fun commitEmpty(
        afterSourceId: Long,
        limit: Int,
        ruleVersion: String,
        actor: Long,
        requestId: String
    ): ApplicationIdentityShadowBatchResult {
        val result = ApplicationIdentityShadowBatchResult(0, 0, 0, 0, 0, 0, afterSourceId, false, ruleVersion)
        appendAudit(result, afterSourceId, limit, actor, requestId)
        return result
    }

    /** 重读 alias 后比较版本快照，再原子写 identity、alias、事实与审计，拒绝事务外旧快照。 */
    @Transactional
    fun commit(plan: ApplicationIdentityShadowPlan): ApplicationIdentityShadowBatchResult {
        val allKeys = plan.keysBySource.values.flatten().distinct()
        val currentAliases = identities.activeAliases(allKeys)
        requireUnchangedAliasSnapshot(plan.aliasSnapshot, currentAliases)
        val outcomes = plan.components.map { component ->
            val keys = component.flatMap { plan.keysBySource.getValue(it.sourceId) }.distinct()
            val mapped = keys.mapNotNull(currentAliases::get).map { it.identity }.distinctBy { it.id }
            ComponentOutcome(component, keys, mapped)
        }
        val newOutcomes = outcomes.filter { it.keys.isNotEmpty() && it.mappedIdentities.isEmpty() }
        val newRows = newOutcomes.map { outcome ->
            NewApplicationIdentity(UUID.randomUUID().toString(), displayName(outcome.sources, outcome.keys))
        }
        val created = identities.createIdentities(newRows)
        val createdByOutcome = newOutcomes.zip(newRows).associate { (outcome, row) ->
            outcome to created.getValue(row.publicId)
        }
        val resolvedTargets = outcomes.mapNotNull { outcome ->
            when {
                outcome.keys.isEmpty() || outcome.mappedIdentities.size > 1 -> null
                outcome.mappedIdentities.size == 1 -> outcome to outcome.mappedIdentities.single()
                else -> outcome to createdByOutcome.getValue(outcome)
            }
        }.toMap()
        val autoBindings = buildAutoBindings(resolvedTargets, currentAliases)
        val existingTargetsToBump = autoBindings.values
            .filter { identity -> identity.publicId !in created.keys }
            .distinctBy { it.id }
        val bumped = identities.incrementVersions(existingTargetsToBump)
        val finalTargets = resolvedTargets.mapValues { (_, target) -> bumped[target.id] ?: target }
        identities.createAutoAliases(autoBindings.mapValues { (_, target) -> bumped[target.id] ?: target })
        val rows = outcomes.flatMap { outcome ->
            outcome.sources.map { input ->
                fact(input, plan.keysBySource.getValue(input.sourceId), outcome, finalTargets[outcome], plan.ruleVersion)
            }
        }.sortedBy { it.feedbackSourceId }
        facts.saveAll(rows)
        val result = ApplicationIdentityShadowBatchResult(
            processedCount = rows.size,
            resolvedCount = rows.count {
                it.applicationResolutionStatus == ApplicationResolutionStatus.RESOLVED
            },
            unresolvedCount = rows.count {
                it.applicationResolutionStatus == ApplicationResolutionStatus.UNRESOLVED
            },
            ambiguousCount = rows.count {
                it.applicationResolutionStatus == ApplicationResolutionStatus.AMBIGUOUS
            },
            createdIdentityCount = newRows.size,
            createdAliasCount = autoBindings.size,
            nextSourceId = plan.inputs.last().sourceId,
            mayHaveMore = plan.inputs.size == plan.limit,
            resolutionRuleVersion = plan.ruleVersion
        )
        appendAudit(result, plan.afterSourceId, plan.limit, plan.actor, plan.requestId)
        return result
    }

    /** 为尚未绑定的 key 建立唯一目标；同键指向不同目标时停止整个批次。 */
    private fun buildAutoBindings(
        resolvedTargets: Map<ComponentOutcome, ApplicationIdentitySnapshot>,
        currentAliases: Map<ApplicationAliasKey, *>
    ): Map<ApplicationAliasKey, ApplicationIdentitySnapshot> {
        val autoBindings = linkedMapOf<ApplicationAliasKey, ApplicationIdentitySnapshot>()
        resolvedTargets.forEach { (outcome, target) ->
            outcome.keys.filterNot(currentAliases::containsKey).forEach { key ->
                if (autoBindings.put(key, target) != null) {
                    sourceIntegrity("APPLICATION_ALIAS_BATCH_TARGET_CONFLICT")
                }
            }
        }
        return autoBindings
    }

    /** 将组件结果转换为强类型主问题事实；无证据和多 identity 分别失败关闭为未解析与歧义。 */
    private fun fact(
        input: ApplicationIdentitySourceInput,
        sourceKeys: List<ApplicationAliasKey>,
        outcome: ComponentOutcome,
        target: ApplicationIdentitySnapshot?,
        ruleVersion: String
    ): PrimaryProblemFact {
        val status = when {
            sourceKeys.isEmpty() -> ApplicationResolutionStatus.UNRESOLVED
            outcome.mappedIdentities.size > 1 -> ApplicationResolutionStatus.AMBIGUOUS
            target != null -> ApplicationResolutionStatus.RESOLVED
            else -> sourceIntegrity("APPLICATION_RESOLUTION_OUTCOME_INVALID")
        }
        return PrimaryProblemFact(
            feedbackSourceId = input.sourceId,
            applicationIdentityId = target?.id,
            applicationPublicIdSnapshot = target?.publicId,
            applicationDisplayNameSnapshot = target?.displayName,
            applicationMappingVersion = target?.mappingVersion,
            applicationResolutionStatus = status,
            classificationStatus = PrimaryProblemClassificationStatus.PENDING,
            resolutionRuleVersion = ruleVersion,
            resolutionReasonCode = when (status) {
                ApplicationResolutionStatus.RESOLVED -> null
                ApplicationResolutionStatus.UNRESOLVED -> ApplicationResolutionFailure.ALIAS_MISSING.code
                ApplicationResolutionStatus.AMBIGUOUS -> ApplicationResolutionFailure.MULTIPLE_IDENTITIES.code
            },
            resolvedAliasCount = sourceKeys.size
        )
    }

    /** 展示名只用于新 identity 呈现；优先审核来源，再退回稳定 alias，绝不作为关联证据。 */
    private fun displayName(
        sources: List<ApplicationIdentitySourceInput>,
        keys: List<ApplicationAliasKey>
    ): String = sources.sortedBy { it.sourceId }.firstNotNullOfOrNull {
        it.displayNameEvidence?.trim()?.takeIf(String::isNotEmpty)?.take(200)
    } ?: keys.firstOrNull { it.aliasType == ApplicationAliasType.SDK_PACKAGE_NAME }?.normalizedAlias
        ?: keys.first().normalizedAlias.take(200)

    /** 影子审计只记录游标、计数与规则版本，不记录 alias 或来源文本。 */
    private fun appendAudit(
        result: ApplicationIdentityShadowBatchResult,
        afterSourceId: Long,
        limit: Int,
        actor: Long,
        requestId: String
    ) {
        audits.append(
            BusinessAuditEvent(
                actor = actor,
                operation = ApplicationIdentityAuditAction.SHADOW_BATCH.code,
                auditClass = ApplicationIdentityAuditClass.BUSINESS.code,
                outcome = ApplicationIdentityAuditOutcome.SUCCESS.code,
                targetType = ApplicationIdentityAuditTarget.SHADOW.code,
                targetId = result.nextSourceId.toString(),
                requestId = requestId,
                clientIp = null,
                detail = mapOf(
                    "afterSourceId" to afterSourceId,
                    "limit" to limit,
                    "processedCount" to result.processedCount,
                    "resolvedCount" to result.resolvedCount,
                    "unresolvedCount" to result.unresolvedCount,
                    "ambiguousCount" to result.ambiguousCount,
                    "createdIdentityCount" to result.createdIdentityCount,
                    "createdAliasCount" to result.createdAliasCount,
                    "resolutionRuleVersion" to result.resolutionRuleVersion
                )
            )
        )
    }

    /** 单个 alias 连通分量及其当前 identity 映射。 */
    private data class ComponentOutcome(
        val sources: List<ApplicationIdentitySourceInput>,
        val keys: List<ApplicationAliasKey>,
        val mappedIdentities: List<ApplicationIdentitySnapshot>
    )
}

/** 影子批次内部不变量失败时停止全部状态推进。 */
private fun sourceIntegrity(reason: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, reason)
