package com.feedback.service.impl.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.strategy.applicationidentity.reviewedBindingCommandFingerprint
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.repository.applicationidentity.ApplicationIdentityReviewCandidateRepository
import com.feedback.model.applicationidentity.ApplicationReviewedBindingCommand
import com.feedback.model.applicationidentity.ApplicationReviewedBindingDecision
import com.feedback.model.applicationidentity.ApplicationReviewedBindingPreflightReport
import com.feedback.service.applicationidentity.ApplicationReviewedBindingPreflightService
import com.feedback.model.applicationidentity.ApplicationReviewedBindingPreparation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** reviewed binding 预检实现；复用正式写入的指纹、候选与版本语义。 */
@Service
class ApplicationReviewedBindingPreflightServiceImpl(
    private val candidates: ApplicationIdentityReviewCandidateRepository,
    private val identities: ApplicationIdentityRepository
) : ApplicationReviewedBindingPreflightService {
    /** 一次批量查询解析所有既有目标，避免离线审核文件按决策产生 N+1。 */
    @Transactional(readOnly = true)
    override fun prepare(
        inputs: List<ApplicationReviewedBindingPreparation>
    ): List<ApplicationReviewedBindingCommand> {
        if (inputs.isEmpty() || inputs.size > ApplicationReviewedBindingPreflightService.MAX_DECISIONS) {
            invalid("APPLICATION_PREFLIGHT_DECISION_COUNT_INVALID")
        }
        val targetNames = inputs.filter {
            it.decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING
        }.map { input ->
            input.targetDisplayName?.trim()?.takeIf(String::isNotEmpty)
                ?: invalid("APPLICATION_PREFLIGHT_TARGET_NAME_REQUIRED")
        }
        val targets = identities.activeByDisplayNames(targetNames)
        return inputs.map { input ->
            val target = when (input.decision) {
                ApplicationReviewedBindingDecision.NEW_APPLICATION -> {
                    if (input.targetDisplayName != null) {
                        invalid("APPLICATION_PREFLIGHT_NEW_TARGET_FORBIDDEN")
                    }
                    null
                }
                ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING -> {
                    val targetName = input.targetDisplayName?.trim()
                        ?: invalid("APPLICATION_PREFLIGHT_TARGET_NAME_REQUIRED")
                    val matches = targets[targetName].orEmpty()
                    if (matches.size != 1) {
                        conflict("APPLICATION_PREFLIGHT_TARGET_NAME_NOT_UNIQUE")
                    }
                    matches.single()
                }
            }
            ApplicationReviewedBindingCommand(
                candidateDisplayName = input.candidateDisplayName.trim(),
                expectedSourceCount = input.expectedSourceCount,
                decision = input.decision,
                targetApplicationId = target?.publicId,
                expectedTargetVersion = target?.mappingVersion,
                requestId = UUID.randomUUID().toString(),
                reason = input.reason.trim()
            )
        }
    }

    /** 相关查询共享短只读快照；没有锁、CAS、审计或外部 I/O。 */
    @Transactional(readOnly = true)
    override fun check(commands: List<ApplicationReviewedBindingCommand>): ApplicationReviewedBindingPreflightReport {
        validateBatch(commands)
        var replayable = 0
        commands.forEach { command ->
            validate(command)
            val fingerprint = reviewedBindingCommandFingerprint(command)
            val existing = identities.findManualResult(command.requestId)
            if (existing != null) {
                if (existing.commandFingerprintHex != fingerprint) {
                    conflict("APPLICATION_REVIEW_REQUEST_CONFLICT")
                }
                replayable++
            } else {
                checkFresh(command)
            }
        }
        return ApplicationReviewedBindingPreflightReport(
            decisionCount = commands.size,
            newApplicationCount = commands.count {
                it.decision == ApplicationReviewedBindingDecision.NEW_APPLICATION
            },
            existingApplicationAliasCount = commands.count {
                it.decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING
            },
            expectedAffectedSourceCount = commands.sumOf { it.expectedSourceCount },
            replayableCount = replayable,
            ready = true
        )
    }

    /** 批量必须非空、有界，且 requestId 与候选展示名各自唯一。 */
    private fun validateBatch(commands: List<ApplicationReviewedBindingCommand>) {
        if (commands.isEmpty() || commands.size > ApplicationReviewedBindingPreflightService.MAX_DECISIONS) {
            invalid("APPLICATION_PREFLIGHT_DECISION_COUNT_INVALID")
        }
        if (commands.map { it.requestId }.distinct().size != commands.size) {
            invalid("APPLICATION_PREFLIGHT_REQUEST_DUPLICATED")
        }
        if (commands.map { it.candidateDisplayName }.distinct().size != commands.size) {
            invalid("APPLICATION_PREFLIGHT_CANDIDATE_DUPLICATED")
        }
    }

    /** 单条命令校验与正式 Service 保持相同 UUID、长度、版本和目标组合约束。 */
    private fun validate(command: ApplicationReviewedBindingCommand) {
        if (command.candidateDisplayName.length !in 1..200) {
            invalid("APPLICATION_REVIEW_DISPLAY_NAME_INVALID")
        }
        if (command.expectedSourceCount <= 0) {
            invalid("APPLICATION_REVIEW_SOURCE_COUNT_INVALID")
        }
        requireUuid(command.requestId)
        if (command.reason.length !in 5..500) {
            invalid("APPLICATION_REVIEW_REASON_INVALID")
        }
        when (command.decision) {
            ApplicationReviewedBindingDecision.NEW_APPLICATION -> {
                if (command.targetApplicationId != null || command.expectedTargetVersion != null) {
                    invalid("APPLICATION_REVIEW_NEW_TARGET_FORBIDDEN")
                }
            }
            ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING -> {
                val target = command.targetApplicationId ?: invalid("APPLICATION_REVIEW_TARGET_REQUIRED")
                requireUuid(target)
                if ((command.expectedTargetVersion ?: 0) <= 0) {
                    invalid("APPLICATION_IDENTITY_VERSION_INVALID")
                }
            }
        }
    }

    /** 检查尚未执行命令的候选 alias、展示名唯一性与目标版本快照。 */
    private fun checkFresh(command: ApplicationReviewedBindingCommand) {
        val candidate = candidates.resolve(command.candidateDisplayName, command.expectedSourceCount)
        if (identities.activeAliases(listOf(candidate.key)).isNotEmpty()) {
            conflict("APPLICATION_REVIEW_ALIAS_ALREADY_BOUND")
        }
        when (command.decision) {
            ApplicationReviewedBindingDecision.NEW_APPLICATION -> {
                if (identities.activeByDisplayName(command.candidateDisplayName).isNotEmpty()) {
                    conflict("APPLICATION_CANONICAL_DISPLAY_NAME_EXISTS")
                }
            }
            ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING -> {
                val targetId = command.targetApplicationId ?: invalid("APPLICATION_REVIEW_TARGET_REQUIRED")
                val target = identities.findByPublicId(targetId)
                    ?: throw ClientException(ErrorCode.NOT_FOUND, "APPLICATION_IDENTITY_NOT_FOUND")
                if (target.mappingVersion != command.expectedTargetVersion) {
                    conflict("APPLICATION_IDENTITY_VERSION_CONFLICT")
                }
            }
        }
    }

    /** 严格 UUID 校验阻止无效 requestId 或目标 ID 进入 MyBatis。 */
    private fun requireUuid(value: String) {
        runCatching { UUID.fromString(value) }.getOrElse {
            invalid("APPLICATION_UUID_INVALID")
        }
    }
}

/** 预检输入错误返回稳定 400 原因码。 */
private fun invalid(reason: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reason)

/** 快照或 requestId 冲突返回稳定 409 原因码。 */
private fun conflict(reason: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_CONFLICT, reason)
