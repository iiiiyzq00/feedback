package com.feedback.manager.applicationidentity

import com.feedback.strategy.applicationidentity.ApplicationIdentityCommandFingerprint
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
import com.feedback.model.applicationidentity.NewApplicationIdentityAlias
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 人工重绑短写事务 Worker；不作为 Controller/Job 可见的业务 Service。 */
@Component
class ApplicationIdentityRebindWorker(
    private val identities: ApplicationIdentityRepository,
    private val audits: BusinessAuditService
) {
    /**
     * 固定锁 alias → source identity → target identity；锁后重查 requestId，并让双 identity CAS、
     * alias 旧版失效、新版写入和必须成功的审计共同提交。
     */
    @Transactional
    fun rebind(command: ApplicationAliasRebindCommand, actor: Long): ApplicationAliasRebindResult {
        val fingerprint = ApplicationIdentityCommandFingerprint.of(
            ApplicationIdentityAuditAction.ALIAS_REBIND.code,
            command.targetApplicationId,
            command.aliasId,
            command.expectedAliasVersion,
            command.expectedTargetVersion,
            command.reason
        )
        identities.findManualResult(command.requestId)?.let { existing ->
            return replayMatching(existing, command, fingerprint)
        }
        val target = identities.findByPublicId(command.targetApplicationId)
            ?: throw ClientException(ErrorCode.NOT_FOUND, "APPLICATION_IDENTITY_NOT_FOUND")
        val alias = identities.lockActiveAlias(command.aliasId) ?: run {
            return replayAfterLock(command, fingerprint)
                ?: throw ClientException(ErrorCode.NOT_FOUND, "APPLICATION_ALIAS_NOT_FOUND")
        }
        identities.lockManualResult(command.requestId)?.let { existing ->
            return replayMatching(existing, command, fingerprint)
        }
        if (alias.aliasVersion != command.expectedAliasVersion) {
            return replayAfterLock(command, fingerprint)
                ?: conflict("APPLICATION_ALIAS_VERSION_CONFLICT")
        }
        val locked = identities.lockIdentitiesInOrder(listOf(alias.applicationIdentityId, target.id))
        val lockedSource = locked[alias.applicationIdentityId]
            ?: sourceIntegrity("APPLICATION_SOURCE_IDENTITY_MISSING")
        val lockedTarget = locked[target.id] ?: sourceIntegrity("APPLICATION_TARGET_IDENTITY_MISSING")
        if (lockedTarget.mappingVersion != command.expectedTargetVersion) {
            return replayAfterLock(command, fingerprint)
                ?: conflict("APPLICATION_IDENTITY_VERSION_CONFLICT")
        }
        val bumped = identities.incrementVersions(listOf(lockedSource, lockedTarget))
        val targetAfter = bumped.getValue(target.id)
        identities.supersedeAlias(alias)
        val newVersion = alias.aliasVersion + 1
        identities.insertManualAlias(
            NewApplicationIdentityAlias(
                aliasPublicId = alias.aliasPublicId,
                key = alias.key,
                activeAliasKeyHex = alias.activeAliasKeyHex
                    ?: sourceIntegrity("APPLICATION_ALIAS_ACTIVE_KEY_MISSING"),
                applicationIdentityId = target.id,
                aliasVersion = newVersion,
                evidenceType = ApplicationAliasEvidenceType.MANUAL_REBIND,
                previousBindingId = alias.id,
                requestId = command.requestId,
                commandFingerprintHex = fingerprint,
                targetMappingVersionSnapshot = targetAfter.mappingVersion,
                changedBy = actor,
                changeReason = command.reason
            )
        )
        appendAudit(command, actor, alias, target.id, targetAfter.mappingVersion, newVersion)
        return ApplicationAliasRebindResult(
            command.requestId,
            alias.aliasPublicId,
            newVersion,
            command.targetApplicationId,
            targetAfter.mappingVersion,
            false
        )
    }

    /** alias 锁后使用当前读确认并发请求是否已经完成。 */
    private fun replayAfterLock(
        command: ApplicationAliasRebindCommand,
        fingerprint: String
    ): ApplicationAliasRebindResult? = identities.lockManualResult(command.requestId)?.let { existing ->
        replayMatching(existing, command, fingerprint)
    }

    /** 同 requestId 只有相同命令指纹可以稳定重放。 */
    private fun replayMatching(
        existing: ApplicationIdentityAliasSnapshot,
        command: ApplicationAliasRebindCommand,
        fingerprint: String
    ): ApplicationAliasRebindResult {
        if (existing.commandFingerprintHex != fingerprint) {
            conflict("APPLICATION_REBIND_REQUEST_CONFLICT")
        }
        return replay(existing, command.targetApplicationId)
    }

    /** 从首次提交保存的结果快照构造重放响应，不读取当前可变版本。 */
    private fun replay(
        existing: ApplicationIdentityAliasSnapshot,
        targetApplicationId: String
    ): ApplicationAliasRebindResult {
        val targetVersion = existing.targetMappingVersionSnapshot
            ?: sourceIntegrity("APPLICATION_REBIND_RESULT_INCOMPLETE")
        return ApplicationAliasRebindResult(
            existing.requestId ?: sourceIntegrity("APPLICATION_REBIND_REQUEST_ID_MISSING"),
            existing.aliasPublicId,
            existing.aliasVersion,
            targetApplicationId,
            targetVersion,
            true
        )
    }

    /** 仅记录 ID、版本与审核原因，不记录 alias 规范化文本或来源正文。 */
    private fun appendAudit(
        command: ApplicationAliasRebindCommand,
        actor: Long,
        alias: ApplicationIdentityAliasSnapshot,
        targetId: Long,
        targetVersion: Long,
        newAliasVersion: Long
    ) {
        audits.append(
            BusinessAuditEvent(
                actor = actor,
                operation = ApplicationIdentityAuditAction.ALIAS_REBIND.code,
                auditClass = ApplicationIdentityAuditClass.BUSINESS.code,
                outcome = ApplicationIdentityAuditOutcome.SUCCESS.code,
                targetType = ApplicationIdentityAuditTarget.ALIAS.code,
                targetId = alias.aliasPublicId,
                requestId = command.requestId,
                clientIp = null,
                detail = mapOf(
                    "sourceIdentityId" to alias.applicationIdentityId,
                    "targetIdentityId" to targetId,
                    "previousAliasVersion" to alias.aliasVersion,
                    "newAliasVersion" to newAliasVersion,
                    "targetMappingVersion" to targetVersion,
                    "reason" to command.reason
                )
            )
        )
    }
}

/** 并发版本或 requestId 指纹冲突。 */
private fun conflict(reason: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_CONFLICT, reason)

/** 锁后聚合成员或历史结果不完整时失败关闭。 */
private fun sourceIntegrity(reason: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, reason)
