package com.feedback.manager.applicationidentity

import com.feedback.strategy.applicationidentity.reviewedBindingCommandFingerprint
import com.feedback.model.applicationidentity.*

import com.feedback.service.applicationidentity.*

import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.applicationidentity.ApplicationAliasEvidenceType
import com.feedback.model.applicationidentity.ApplicationIdentityAliasSnapshot
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.repository.applicationidentity.ApplicationIdentityReviewCandidateRepository
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import com.feedback.model.applicationidentity.NewApplicationIdentity
import com.feedback.model.applicationidentity.NewApplicationIdentityAlias
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** reviewed binding 短写事务 Worker；展示名只定位已审核候选，不作为 alias 证据。 */
@Component
class ApplicationIdentityReviewedBindingWorker(
    private val candidates: ApplicationIdentityReviewCandidateRepository,
    private val identities: ApplicationIdentityRepository,
    private val audits: BusinessAuditService
) {
    /** requestId 复核、候选快照确认、identity/alias 写入与审计在同一短事务共同提交。 */
    @Transactional
    fun create(command: ApplicationReviewedBindingCommand, actor: Long): ApplicationReviewedBindingResult {
        val fingerprint = reviewedBindingCommandFingerprint(command)
        identities.findManualResult(command.requestId)?.let { existing ->
            return replayMatching(existing, command, fingerprint)
        }
        val candidate = candidates.resolve(command.candidateDisplayName, command.expectedSourceCount)
        if (identities.activeAliases(listOf(candidate.key)).isNotEmpty()) {
            conflict("APPLICATION_REVIEW_ALIAS_ALREADY_BOUND")
        }
        identities.lockManualResult(command.requestId)?.let { existing ->
            return replayMatching(existing, command, fingerprint)
        }
        val target = when (command.decision) {
            ApplicationReviewedBindingDecision.NEW_APPLICATION -> createNewIdentity(command, actor)
            ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING -> lockAndBumpTarget(command)
        }
        val aliasId = UUID.randomUUID().toString()
        identities.insertManualAlias(
            NewApplicationIdentityAlias(
                aliasPublicId = aliasId,
                key = candidate.key,
                activeAliasKeyHex = identities.activeKeyHex(candidate.key),
                applicationIdentityId = target.id,
                evidenceType = ApplicationAliasEvidenceType.MANUAL_REBIND,
                requestId = command.requestId,
                commandFingerprintHex = fingerprint,
                targetMappingVersionSnapshot = target.mappingVersion,
                changedBy = actor,
                changeReason = command.reason
            )
        )
        appendAudit(command, actor, target, aliasId, candidate.sourceCount)
        return result(command, target, aliasId, 1, candidate.sourceCount, false)
    }

    /** 新建决策在插入前再次确认活动展示名唯一，数据库唯一键是最终并发栅栏。 */
    private fun createNewIdentity(
        command: ApplicationReviewedBindingCommand,
        actor: Long
    ): ApplicationIdentitySnapshot {
        if (identities.activeByDisplayName(command.candidateDisplayName).isNotEmpty()) {
            conflict("APPLICATION_CANONICAL_DISPLAY_NAME_EXISTS")
        }
        val publicId = UUID.randomUUID().toString()
        return identities.createIdentities(
            listOf(NewApplicationIdentity(publicId, command.candidateDisplayName, actor))
        ).getValue(publicId)
    }

    /** 既有 identity 决策锁定目标后执行版本 CAS，拒绝审核后发生的映射漂移。 */
    private fun lockAndBumpTarget(command: ApplicationReviewedBindingCommand): ApplicationIdentitySnapshot {
        val publicId = command.targetApplicationId ?: sourceIntegrity("APPLICATION_REVIEW_TARGET_ID_MISSING")
        val snapshot = identities.findByPublicId(publicId)
            ?: throw ClientException(ErrorCode.NOT_FOUND, "APPLICATION_IDENTITY_NOT_FOUND")
        val locked = identities.lockIdentitiesInOrder(listOf(snapshot.id))[snapshot.id]
            ?: sourceIntegrity("APPLICATION_TARGET_IDENTITY_MISSING")
        if (locked.mappingVersion != command.expectedTargetVersion) {
            conflict("APPLICATION_IDENTITY_VERSION_CONFLICT")
        }
        return identities.incrementVersions(listOf(locked)).getValue(locked.id)
    }

    /** 同 requestId 仅允许命令指纹完全一致的稳定重放。 */
    private fun replayMatching(
        existing: ApplicationIdentityAliasSnapshot,
        command: ApplicationReviewedBindingCommand,
        fingerprint: String
    ): ApplicationReviewedBindingResult {
        if (existing.commandFingerprintHex != fingerprint) {
            conflict("APPLICATION_REVIEW_REQUEST_CONFLICT")
        }
        val target = identities.findById(existing.applicationIdentityId)
            ?: sourceIntegrity("APPLICATION_REVIEW_TARGET_IDENTITY_MISSING")
        return result(
            command,
            target.copy(mappingVersion = existing.targetMappingVersionSnapshot ?: target.mappingVersion),
            existing.aliasPublicId,
            existing.aliasVersion,
            command.expectedSourceCount,
            true
        )
    }

    /** 构造强类型 reviewed binding 结果，HTTP code 转换留在 Controller 边界。 */
    private fun result(
        command: ApplicationReviewedBindingCommand,
        target: ApplicationIdentitySnapshot,
        aliasId: String,
        aliasVersion: Long,
        affectedSourceCount: Long,
        replayed: Boolean
    ) = ApplicationReviewedBindingResult(
        command.requestId,
        command.decision,
        target.publicId,
        aliasId,
        aliasVersion,
        target.mappingVersion,
        affectedSourceCount,
        replayed
    )

    /** 审计只包含决策、ID、版本、计数与人工原因，不写候选展示名或来源正文。 */
    private fun appendAudit(
        command: ApplicationReviewedBindingCommand,
        actor: Long,
        target: ApplicationIdentitySnapshot,
        aliasId: String,
        affectedSourceCount: Long
    ) {
        audits.append(
            BusinessAuditEvent(
                actor = actor,
                operation = ApplicationIdentityAuditAction.REVIEWED_BINDING.code,
                auditClass = ApplicationIdentityAuditClass.BUSINESS.code,
                outcome = ApplicationIdentityAuditOutcome.SUCCESS.code,
                targetType = ApplicationIdentityAuditTarget.ALIAS.code,
                targetId = aliasId,
                requestId = command.requestId,
                clientIp = null,
                detail = mapOf(
                    "decision" to command.decision.code,
                    "targetApplicationId" to target.id,
                    "targetMappingVersion" to target.mappingVersion,
                    "affectedSourceCount" to affectedSourceCount,
                    "reason" to command.reason
                )
            )
        )
    }
}

/** reviewed binding 的业务并发冲突统一使用 409 错误码。 */
private fun conflict(reason: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_CONFLICT, reason)

/** 事务内目标快照缺失时失败关闭，禁止产出不完整绑定。 */
private fun sourceIntegrity(reason: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, reason)
