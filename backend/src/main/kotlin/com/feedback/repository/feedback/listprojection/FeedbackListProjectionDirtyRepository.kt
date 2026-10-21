package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionDirtyMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionDirtyPo
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionDirtyVersionPo
import org.springframework.stereotype.Repository

enum class FeedbackListProjectionDirtyReason {
    SOURCE, CONTENT, AI, WORKFLOW, ASSIGNMENT, HANDOFF, ATTACHMENT, IMPORT, CLUSTER, IDENTITY, CHANNEL
}

data class FeedbackListProjectionDirtyClaim(
    val leaseOwner: String,
    val rows: List<FeedbackListProjectionDirtyPo>
) {
    val sourceIds: List<Long> get() = rows.map(FeedbackListProjectionDirtyPo::sourceId)
    val versions: List<FeedbackListProjectionDirtyVersionPo> get() = rows.map {
        FeedbackListProjectionDirtyVersionPo(it.sourceId, it.mutationVersion)
    }
}

/**
 * 投影脏标记的单表持久化边界。
 *
 * 领取、确认和失败更新都使用 source ID + mutation version；并发新变更递增版本后，旧 Worker
 * 无法删除新标记。所有集合均受 200 条上限约束，空集合不会触发 SQL。
 */
@Repository
class FeedbackListProjectionDirtyRepository(
    private val mapper: FeedbackListProjectionDirtyMapper
) {
    /** control row 已锁定后复核任意状态 dirty；返回非空即禁止切换或回滚。 */
    fun lockFirstDirty(): FeedbackListProjectionDirtyPo? = mapper.findFirstForUpdate()

    fun mark(sourceIds: List<Long>, reason: FeedbackListProjectionDirtyReason) {
        val ids = boundedIds(sourceIds, allowEmpty = false)
        if (mapper.upsertDirty(ids, reason.name) <= 0) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_DIRTY_MARK_FAILED")
    }

    fun claim(
        preferredSourceIds: List<Long>?,
        limit: Int,
        leaseOwner: String,
        leaseSeconds: Long
    ): FeedbackListProjectionDirtyClaim? {
        if (limit !in 1..FeedbackListProjectionRepository.MAX_BATCH_SIZE) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_DIRTY_CLAIM_LIMIT_INVALID")
        if (leaseOwner.isBlank() || leaseOwner.length > 80) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_DIRTY_LEASE_OWNER_INVALID")
        if (leaseSeconds !in 1..MAX_DELAY_SECONDS) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_DIRTY_LEASE_INVALID")
        val preferred = preferredSourceIds?.let { boundedIds(it, allowEmpty = false) }
        val rows = mapper.findClaimableForUpdate(preferred, limit)
        if (rows.isEmpty()) return null
        val versions = rows.map { FeedbackListProjectionDirtyVersionPo(it.sourceId, it.mutationVersion) }
        if (mapper.markProcessing(versions, leaseOwner, leaseSeconds) != rows.size) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_DIRTY_CLAIM_FAILED")
        return FeedbackListProjectionDirtyClaim(leaseOwner, rows)
    }

    fun acknowledge(claim: FeedbackListProjectionDirtyClaim): Int =
        mapper.deleteClaimed(claim.versions, claim.leaseOwner)

    fun release(claim: FeedbackListProjectionDirtyClaim, delaySeconds: Long): Int {
        requireDelay(delaySeconds)
        return mapper.releaseClaimed(claim.versions, claim.leaseOwner, delaySeconds)
    }

    fun retry(
        claim: FeedbackListProjectionDirtyClaim,
        delaySeconds: Long,
        errorCode: String,
        errorSummary: String
    ): Int {
        requireDelay(delaySeconds)
        return mapper.markRetry(
            claim.versions,
            claim.leaseOwner,
            delaySeconds,
            boundedError(errorCode, 80),
            boundedError(errorSummary, 500)
        )
    }

    fun fail(claim: FeedbackListProjectionDirtyClaim, errorCode: String, errorSummary: String): Int =
        mapper.markFailed(
            claim.versions,
            claim.leaseOwner,
            boundedError(errorCode, 80),
            boundedError(errorSummary, 500)
        )

    private fun boundedIds(ids: List<Long>, allowEmpty: Boolean): List<Long> {
        val normalized = ids.distinct().sorted()
        if (!allowEmpty && normalized.isEmpty()) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_DIRTY_SOURCE_IDS_EMPTY")
        if (normalized.size > FeedbackListProjectionRepository.MAX_BATCH_SIZE) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_BATCH_TOO_LARGE")
        if (normalized.any { it <= 0 }) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_SOURCE_ID_INVALID")
        return normalized
    }

    private fun boundedError(value: String, maxLength: Int): String {
        if (value.isBlank() || value.length > maxLength || value.any(Char::isISOControl)) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_DIRTY_ERROR_INVALID")
        return value
    }

    private fun requireDelay(delaySeconds: Long) {
        if (delaySeconds !in 1..MAX_DELAY_SECONDS) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_DIRTY_DELAY_INVALID")
    }

    companion object { const val MAX_DELAY_SECONDS = 3600L }
}
