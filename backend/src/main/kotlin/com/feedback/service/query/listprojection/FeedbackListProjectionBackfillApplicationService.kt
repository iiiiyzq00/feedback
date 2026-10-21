package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 默认关闭的 Backfill Runner 与事务协调器之间的应用层编排。
 *
 * 每次调用只执行一个明确动作；REFRESH 依次调用两个独立事务完成追尾准备和单批刷新，不在应用层循环，也不
 * 把两步伪装成原子操作。只有刷新/验证执行阶段抛出的 BuildExecutionException 才使用固定清洗事实在新事务
 * 中关闭 BUILDING；输入、操作者、状态、dirty 或 CatchUpRequired 前置拒绝保持原状态并向调用方传播。
 *
 * 失败关闭本身若失败，只记录不含原始异常和业务内容的固定运维日志，绝不遮蔽最初的 BuildExecutionException。
 * CREATE、ACTIVATE、ROLLBACK 的事务原子性和 CAS 失败语义完全由 Coordinator 保证，本层不捕获后继续执行。
 */
@Service
class FeedbackListProjectionBackfillApplicationService(
    private val coordinator: FeedbackListProjectionMutationCoordinator
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 创建 BUILDING 快照并记录 generation ID、状态和处理数量；返回不代表已刷新任何 source。 */
    fun create(command: FeedbackListProjectionBuildCommand): FeedbackListProjectionBuildResult =
        coordinator.createBuildingSnapshot(command).also(::logBuild)

    /**
     * 先提交一次 fence 追尾准备，再在另一事务刷新最多一个批次。
     * BuildExecutionException 会触发尽力而为但失败关闭的独立事务；CatchUpRequired 和其他前置拒绝不关闭槽位。
     */
    fun refreshNextBatch(command: FeedbackListProjectionBuildCommand): FeedbackListProjectionBuildResult {
        FeedbackListProjectionBackfillValidation.action(command.action, "REFRESH")
        FeedbackListProjectionBackfillValidation.batchSize(command.batchSize)
        val key = FeedbackListProjectionBackfillValidation.generationKey(command.generationKey)
        return try {
            val normalized = command.copy(generationKey = key)
            coordinator.prepareBuildingCatchUp(normalized)
            coordinator.refreshNextBatch(normalized).also(::logBuild)
        } catch (error: ServiceException) {
            if (error.errorCode != ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED) throw error
            runCatching {
                coordinator.failBuilding(
                    key,
                    "FEEDBACK_LIST_PROJECTION_BUILD_BATCH_FAILED",
                    "Projection build batch failed; generation closed"
                )
            }.onFailure {
                logger.error(
                    "Feedback projection generation failure closure failed: status=BUILDING, errorCode={}",
                    "FEEDBACK_LIST_PROJECTION_FAILURE_CLOSURE_FAILED"
                )
            }
            throw error
        }
    }

    /**
     * 验证已追平的 BUILDING 并保存完整性事实。验证执行失败关闭槽位；在线 source 尾部变化产生的
     * CatchUpRequired 保持 BUILDING，供下一次受控 REFRESH 继续收敛。
     */
    fun verify(command: FeedbackListProjectionBuildCommand): FeedbackListProjectionVerificationResult {
        FeedbackListProjectionBackfillValidation.action(command.action, "VERIFY")
        FeedbackListProjectionBackfillValidation.batchSize(command.batchSize)
        val normalized = command.copy(generationKey = FeedbackListProjectionBackfillValidation.generationKey(command.generationKey))
        return try {
            coordinator.verifyBuilding(normalized).also { result ->
                logger.info(
                    "Feedback projection generation verified: generationId={}, projectedCount={}, rejectedCount={}, status=BUILDING",
                    result.generationId, result.projectedSourceCount, result.rejectedSourceCount
                )
            }
        } catch (error: ServiceException) {
            if (error.errorCode != ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED) throw error
            runCatching {
                coordinator.failBuilding(
                    normalized.generationKey,
                    "FEEDBACK_LIST_PROJECTION_VERIFICATION_FAILED",
                    "Projection verification failed; generation closed"
                )
            }.onFailure {
                logger.error(
                    "Feedback projection verification failure closure failed: status=BUILDING, errorCode={}",
                    "FEEDBACK_LIST_PROJECTION_FAILURE_CLOSURE_FAILED"
                )
            }
            throw error
        }
    }

    /** 原子激活已验证 generation；不在本层重试、降级或吞掉 epoch/checksum/CAS 失败。 */
    fun activate(command: FeedbackListProjectionActivationCommand): FeedbackListProjectionActivationResult =
        coordinator.activateVerified(command).also { result ->
            logger.info("Feedback projection generation activated: generationId={}, status={}", result.generationId, result.generationStatus)
        }

    /** 原子恢复唯一 ROLLBACK_READY；不在本层绕过 dirty、epoch、checksum 或 CAS 门禁。 */
    fun rollback(command: FeedbackListProjectionRollbackCommand): FeedbackListProjectionRollbackResult =
        coordinator.rollback(command).also { result ->
            logger.info("Feedback projection generation rolled back: generationId={}, status={}", result.generationId, result.generationStatus)
        }

    private fun logBuild(result: FeedbackListProjectionBuildResult) {
        logger.info(
            "Feedback projection generation build step: generationId={}, status={}, processedCount={}",
            result.generationId, result.generationStatus, result.processedCount
        )
    }
}
