package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

enum class FeedbackListProjectionDrainStatus {
    NO_WORK, NO_WRITABLE_GENERATION, REFRESHED, RETRY_SCHEDULED, FAILED
}

data class FeedbackListProjectionDrainResult(
    val status: FeedbackListProjectionDrainStatus,
    val claimedCount: Int = 0,
    val acknowledgedCount: Int = 0
)

/**
 * 提交后即时刷新与定时补偿共用的投影队列消费者。
 *
 * 领取事务保持短小；投影刷新不持有 dirty 行锁。并发新变更会递增 mutation version 并清除旧租约，
 * 因而旧消费者的条件确认不会删除新任务。错误摘要固定生成，不记录异常消息或业务数据。
 */
@Component
class FeedbackListProjectionRefreshWorker(
    private val coordinator: FeedbackListProjectionMutationCoordinator
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val workerId = "feedback-list-${UUID.randomUUID()}"

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    fun afterDirtyCommitted(event: FeedbackListProjectionDirtyCommittedEvent) {
        runCatching { drainOnce(event.sourceIds) }
            .onFailure { logger.warn("Feedback list projection immediate refresh failed: {}", it.javaClass.simpleName) }
    }

    fun drainScheduled() {
        runCatching { drainOnce() }
            .onFailure { logger.warn("Feedback list projection compensation cycle failed: {}", it.javaClass.simpleName) }
    }

    fun drainOnce(preferredSourceIds: List<Long>? = null): FeedbackListProjectionDrainResult {
        val claim = coordinator.claimDirty(
            preferredSourceIds = preferredSourceIds,
            limit = MAX_BATCH_SIZE,
            leaseOwner = workerId,
            leaseSeconds = LEASE_SECONDS
        ) ?: return FeedbackListProjectionDrainResult(FeedbackListProjectionDrainStatus.NO_WORK)
        return try {
            val refreshed = coordinator.refreshIncremental(
                FeedbackListProjectionIncrementalRefreshCommand(claim.sourceIds)
            )
            if (!refreshed.hasWritableGeneration) {
                coordinator.deferDirty(claim, NO_GENERATION_DELAY_SECONDS)
                FeedbackListProjectionDrainResult(
                    FeedbackListProjectionDrainStatus.NO_WRITABLE_GENERATION,
                    claimedCount = claim.rows.size
                )
            } else {
                val acknowledged = coordinator.acknowledgeDirty(claim)
                FeedbackListProjectionDrainResult(
                    FeedbackListProjectionDrainStatus.REFRESHED,
                    claimedCount = claim.rows.size,
                    acknowledgedCount = acknowledged
                )
            }
        } catch (error: Exception) {
            val code = error.javaClass.simpleName.take(80).ifBlank { "ProjectionRefreshException" }
            val summary = "Projection refresh failed; retained for governed retry"
            val (failedRows, retryRows) = claim.rows.partition { it.retryCount + 1 >= MAX_RETRY_COUNT }
            if (failedRows.isNotEmpty()) {
                coordinator.failDirty(claim.copy(rows = failedRows), code, summary)
                logger.error(
                    "Feedback list projection dirty rows exhausted governed retries: count={}, errorCode={}",
                    failedRows.size,
                    code
                )
            }
            if (retryRows.isNotEmpty()) {
                val highestNextAttempt = (retryRows.maxOfOrNull { it.retryCount }
                    ?: throw ServiceException(ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED, "PROJECTION_RETRY_ROWS_MISSING")) + 1
                val delaySeconds = (BASE_RETRY_SECONDS shl (highestNextAttempt - 1).coerceAtMost(5))
                    .coerceAtMost(MAX_RETRY_SECONDS)
                coordinator.retryDirty(claim.copy(rows = retryRows), delaySeconds, code, summary)
                logger.warn(
                    "Feedback list projection refresh retained for retry: count={}, errorCode={}",
                    retryRows.size,
                    code
                )
            }
            if (retryRows.isEmpty()) {
                FeedbackListProjectionDrainResult(
                    FeedbackListProjectionDrainStatus.FAILED,
                    claimedCount = claim.rows.size
                )
            } else {
                FeedbackListProjectionDrainResult(
                    FeedbackListProjectionDrainStatus.RETRY_SCHEDULED,
                    claimedCount = claim.rows.size
                )
            }
        }
    }

    companion object {
        const val MAX_BATCH_SIZE = 200
        const val LEASE_SECONDS = 60L
        const val NO_GENERATION_DELAY_SECONDS = 30L
        const val MAX_RETRY_COUNT = 8
        const val BASE_RETRY_SECONDS = 5L
        const val MAX_RETRY_SECONDS = 300L
    }
}
