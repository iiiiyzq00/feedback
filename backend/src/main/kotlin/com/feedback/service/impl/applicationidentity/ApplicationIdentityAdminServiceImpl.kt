package com.feedback.service.impl.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.applicationidentity.ApplicationIdentityAliasSnapshot
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.model.applicationidentity.ApplicationResolutionStatus
import com.feedback.repository.applicationidentity.PrimaryProblemFactRepository
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.model.applicationidentity.ApplicationAliasCountItem
import com.feedback.model.applicationidentity.ApplicationAliasRebindCommand
import com.feedback.model.applicationidentity.ApplicationAliasRebindResult
import com.feedback.service.applicationidentity.ApplicationIdentityAdminService
import com.feedback.model.applicationidentity.ApplicationIdentityAliasItem
import com.feedback.model.applicationidentity.ApplicationIdentityDiagnostics
import com.feedback.model.applicationidentity.ApplicationIdentityListItem
import com.feedback.model.applicationidentity.ApplicationIdentityPage
import com.feedback.manager.applicationidentity.ApplicationIdentityRebindWorker
import com.feedback.service.applicationidentity.ApplicationIdentityShadowService
import com.feedback.manager.applicationidentity.ApplicationIdentityReviewedBindingWorker
import com.feedback.model.applicationidentity.ApplicationReviewedBindingCommand
import com.feedback.model.applicationidentity.ApplicationReviewedBindingDecision
import com.feedback.model.applicationidentity.ApplicationReviewedBindingResult
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 管理员应用身份 Service 实现；鉴权、边界校验与短事务编排集中于此。 */
@Service
class ApplicationIdentityAdminServiceImpl(
    private val identities: ApplicationIdentityRepository,
    private val facts: PrimaryProblemFactRepository,
    private val principalProvider: CurrentWebPrincipalProvider,
    private val rebindWorker: ApplicationIdentityRebindWorker,
    private val reviewedBindingWorker: ApplicationIdentityReviewedBindingWorker
) : ApplicationIdentityAdminService {
    /** 两次相关聚合查询使用短只读事务，结果不携带 alias 原文或反馈正文。 */
    @Transactional(readOnly = true)
    override fun diagnostics(): ApplicationIdentityDiagnostics {
        principalProvider.require()
        val resolution = facts.resolutionCounts()
        return ApplicationIdentityDiagnostics(
            totalShadowFacts = resolution.values.sum(),
            resolvedCount = resolution[ApplicationResolutionStatus.RESOLVED] ?: 0,
            unresolvedCount = resolution[ApplicationResolutionStatus.UNRESOLVED] ?: 0,
            ambiguousCount = resolution[ApplicationResolutionStatus.AMBIGUOUS] ?: 0,
            activeAliasCounts = identities.aliasTypeCounts().toSortedMap().map {
                ApplicationAliasCountItem(it.key, it.value)
            },
            resolutionRuleVersion = ApplicationIdentityShadowService.RESOLUTION_RULE_VERSION
        )
    }

    /** 单条有界查询依赖 InnoDB 自动提交，不额外占用事务连接。 */
    override fun list(afterApplicationId: String?, limit: Int): ApplicationIdentityPage {
        principalProvider.require()
        val rows = identities.listAfter(afterApplicationId, limit)
        return ApplicationIdentityPage(
            rows.map { row ->
                ApplicationIdentityListItem(row.publicId, row.displayName, row.lifecycleStatus, row.mappingVersion)
            },
            rows.lastOrNull()?.publicId?.takeIf { rows.size == limit }
        )
    }

    /** identity 存在性与 alias 历史属于同一只读快照，历史上限由 Repository 强制。 */
    @Transactional(readOnly = true)
    override fun aliases(applicationId: String): List<ApplicationIdentityAliasItem> {
        principalProvider.require()
        requireUuid(applicationId)
        val identity = identities.findByPublicId(applicationId)
            ?: throw ClientException(ErrorCode.NOT_FOUND, "APPLICATION_IDENTITY_NOT_FOUND")
        return identities.aliasesFor(identity.id).map { row -> row.item(applicationId) }
    }

    /** 规范化与鉴权在事务外完成；唯一键冲突最多安全重试一次独立短写事务。 */
    override fun rebind(command: ApplicationAliasRebindCommand): ApplicationAliasRebindResult {
        validate(command)
        val actor = principalProvider.require().userId
        return retryDuplicateOnce { rebindWorker.rebind(command.copy(reason = command.reason.trim()), actor) }
    }

    /** reviewed binding 仅对唯一键竞争重试一次，锁后 requestId 复核决定重放或冲突。 */
    override fun createReviewedBinding(command: ApplicationReviewedBindingCommand): ApplicationReviewedBindingResult {
        validate(command)
        val actor = principalProvider.require().userId
        val normalized = command.copy(
            candidateDisplayName = command.candidateDisplayName.trim(),
            reason = command.reason.trim()
        )
        return retryDuplicateOnce { reviewedBindingWorker.create(normalized, actor) }
    }

    /** 校验重绑 UUID、双版本 CAS 输入及不含正文的有界原因。 */
    private fun validate(command: ApplicationAliasRebindCommand) {
        requireUuid(command.targetApplicationId)
        requireUuid(command.aliasId)
        requireUuid(command.requestId)
        if (command.expectedAliasVersion <= 0) {
            invalid("APPLICATION_ALIAS_VERSION_INVALID")
        }
        if (command.expectedTargetVersion <= 0) {
            invalid("APPLICATION_IDENTITY_VERSION_INVALID")
        }
        if (command.reason.trim().length !in REASON_LENGTH) {
            invalid("APPLICATION_REBIND_REASON_INVALID")
        }
    }

    /** 校验 reviewed binding 决策与目标字段组合，避免无效命令进入锁事务。 */
    private fun validate(command: ApplicationReviewedBindingCommand) {
        if (command.candidateDisplayName.trim().length !in DISPLAY_NAME_LENGTH) {
            invalid("APPLICATION_REVIEW_DISPLAY_NAME_INVALID")
        }
        if (command.expectedSourceCount <= 0) {
            invalid("APPLICATION_REVIEW_SOURCE_COUNT_INVALID")
        }
        requireUuid(command.requestId)
        if (command.reason.trim().length !in REASON_LENGTH) {
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

    /** requestId/公有 ID 使用严格 UUID 语法，保持 UUID_TO_BIN 数据库契约。 */
    private fun requireUuid(value: String) {
        runCatching { UUID.fromString(value) }.getOrElse {
            invalid("APPLICATION_UUID_INVALID")
        }
    }

    /** 仅捕获唯一键竞争且最多重试一次；业务冲突和非幂等阶段从不重试。 */
    private fun <T> retryDuplicateOnce(action: () -> T): T = try {
        action()
    } catch (_: DuplicateKeyException) {
        action()
    }

    /** 将强类型 alias 历史转换为 Service 读模型，不改变外部稳定 code。 */
    private fun ApplicationIdentityAliasSnapshot.item(applicationId: String) = ApplicationIdentityAliasItem(
        aliasPublicId,
        key.sourceChannel,
        key.aliasType,
        key.normalizedAlias,
        aliasVersion,
        bindingStatus,
        applicationId,
        evidenceType,
        requestId
    )

    companion object {
        private val REASON_LENGTH = 5..500
        private val DISPLAY_NAME_LENGTH = 1..200
    }
}

/** 将 Service 输入错误映射为稳定 400 原因码。 */
private fun invalid(reason: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reason)
