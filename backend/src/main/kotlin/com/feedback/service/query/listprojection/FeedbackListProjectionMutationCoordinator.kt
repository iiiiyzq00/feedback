package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionControlRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyClaim
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionVerifiedFacts
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 反馈列表投影 generation 状态变化和 dirty 租约收口的唯一事务入口。
 *
 * CREATE、VERIFY、ACTIVATE、ROLLBACK 和失败关闭在这里固定治理锁与状态 CAS；REFRESH 被拆成可重启的
 * 追尾准备和单批刷新事务，避免一次进程启动长时间持锁。切换与回滚固定锁序为 control → generation ID
 * 升序 → dirty，任何 CAS、epoch 或 checksum 不一致都整体回滚，不做旧路径或部分成功兜底。
 *
 * generation 运维动作只由默认关闭的 Backfill Runner 经应用服务调用；dirty 领取、增量同步和租约结算则由
 * Dirty Worker 调用。actor 只作为受控运维归因，并在单表边界确认仍为活动用户；这里不建立 HTTP 登录主体，
 * 也不推断或扩张业务角色权限。
 */
@Component
class FeedbackListProjectionMutationCoordinator(
    private val repository: FeedbackListProjectionRepository,
    private val synchronizer: FeedbackListProjectionSynchronizer,
    private val dirtyRepository: FeedbackListProjectionDirtyRepository,
    private val controlRepository: FeedbackListProjectionControlRepository,
    private val verifier: FeedbackListProjectionGenerationVerifier
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    /**
     * 固定当前权威 source 尾部和 fence 内记录数，创建唯一 BUILDING 快照。
     *
     * 事务先锁 control，再按 ID 锁定全部受管 generation；存在 BUILDING、操作者失效或基线读取失败时不插入。
     * 返回值只表示快照槽位已创建，cursor 仍为起点，不代表任何投影已刷新或验证。
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun createBuildingSnapshot(command: FeedbackListProjectionBuildCommand): FeedbackListProjectionBuildResult {
        FeedbackListProjectionBackfillValidation.action(command.action, "CREATE")
        val key = FeedbackListProjectionBackfillValidation.generationKey(command.generationKey)
        FeedbackListProjectionBackfillValidation.batchSize(command.batchSize)
        val epoch = controlRepository.lockControl()
        if (epoch < 0) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_CONTROL_EPOCH_INVALID")
        controlRepository.requireActiveActor(command.actorId)
        if (repository.lockManagedGenerations().any { it.generationStatus == "BUILDING" }) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_BUILDING_ALREADY_EXISTS")
        val fence = repository.sourceFence()
        val baseline = repository.sourceCountThroughFence(fence)
        val generation = repository.createBuilding(key, fence, baseline, command.actorId)
        return FeedbackListProjectionBuildResult(generation.id, generation.generationStatus, 0, 0, fence)
    }

    /**
     * 在独立事务中判断 BUILDING 是否需要把 fence 扩展到新的 source 尾部。
     *
     * cursor 尚未追平旧 fence 时保持所有事实不变；追平后在 control → generation 锁序下捕获新 fence/基线并
     * 以 CAS 扩展，扩展同时清除旧验证事实。该事务提交后才允许下一批刷新，因此在线写入可继续发生，调用方
     * 必须通过后续 REFRESH/VERIFY 反复收敛，不能把一次返回理解为已追平实时尾部。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    fun prepareBuildingCatchUp(command: FeedbackListProjectionBuildCommand): FeedbackListProjectionBuildResult {
        FeedbackListProjectionBackfillValidation.action(command.action, "REFRESH")
        val key = FeedbackListProjectionBackfillValidation.generationKey(command.generationKey)
        FeedbackListProjectionBackfillValidation.batchSize(command.batchSize)
        controlRepository.lockControl()
        controlRepository.requireActiveActor(command.actorId)
        val generation = repository.lockByKey(key)
        if (generation.generationStatus != "BUILDING") projectionOperationFailed("FEEDBACK_LIST_PROJECTION_GENERATION_NOT_BUILDING")
        val fence = generation.buildFenceSourceId ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_FENCE_MISSING")
        val cursor = generation.buildCursorSourceId ?: 0L
        if (cursor > fence) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_CURSOR_INVALID")
        if (cursor < fence) {
            return FeedbackListProjectionBuildResult(generation.id, "BUILDING", 0, cursor, fence)
        }
        try {
            val nextFence = repository.sourceFence()
            if (nextFence < fence) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_SOURCE_FENCE_REGRESSED")
            val nextCount = repository.sourceCountThroughFence(nextFence)
            if (nextFence != fence || nextCount != generation.expectedSourceCount) {
                repository.extendBuildFence(
                    generation.id, fence, generation.expectedSourceCount, nextFence, nextCount
                )
            }
            return FeedbackListProjectionBuildResult(generation.id, "BUILDING", 0, cursor, nextFence)
        } catch (error: Exception) {
            logger.error(
                "Feedback projection batch execution failed: generationId={}, errorType={}",
                generation.id,
                error.javaClass.name,
                error
            )
            throw ServiceException(
                ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED,
                "FEEDBACK_LIST_PROJECTION_BUILD_EXECUTION_FAILED",
                cause = error
            )
        }
    }

    /**
     * 在独立事务中按 source ID keyset 刷新 BUILDING 的下一批，单批上限 200。
     *
     * generation 治理锁只锁目标 BUILDING；没有后续 ID 时把持久化 cursor 对齐 fence。Synchronizer 负责安全
     * 资格先行、投影替换和 cursor 推进，任一步失败都回滚本批并转换为固定 BuildExecutionException，由应用
     * 服务另启事务关闭 generation。它不与 prepareBuildingCatchUp 组成跨事务原子操作。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    fun refreshNextBatch(command: FeedbackListProjectionBuildCommand): FeedbackListProjectionBuildResult {
        FeedbackListProjectionBackfillValidation.action(command.action, "REFRESH")
        val key = FeedbackListProjectionBackfillValidation.generationKey(command.generationKey)
        val limit = FeedbackListProjectionBackfillValidation.batchSize(command.batchSize)
        controlRepository.requireActiveActor(command.actorId)
        val generation = repository.lockByKey(key)
        if (generation.generationStatus != "BUILDING") projectionOperationFailed("FEEDBACK_LIST_PROJECTION_GENERATION_NOT_BUILDING")
        try {
            val fence = generation.buildFenceSourceId ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_FENCE_MISSING")
            val cursor = generation.buildCursorSourceId ?: 0L
            if (cursor > fence) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_CURSOR_INVALID")
            val ids = repository.nextSourceIds(cursor, fence, limit)
            if (ids.isEmpty()) {
                if (generation.buildCursorSourceId != fence) repository.advanceBuild(generation.id, fence, 0, 0)
                return FeedbackListProjectionBuildResult(generation.id, "BUILDING", 0, fence, fence)
            }
            val refreshed = synchronizer.refresh(FeedbackListProjectionRefreshCommand(generation.id, ids))
            return FeedbackListProjectionBuildResult(
                generation.id, "BUILDING", refreshed.requestedCount, refreshed.cursorSourceId, fence
            )
        } catch (error: Exception) {
            logger.error(
                "Feedback projection batch execution failed: generationId={}, errorType={}",
                generation.id,
                error.javaClass.name,
                error
            )
            throw ServiceException(
                ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED,
                "FEEDBACK_LIST_PROJECTION_BUILD_EXECUTION_FAILED",
                cause = error
            )
        }
    }

    /**
     * 在固定 control epoch 下验证已追平且无 dirty 的 BUILDING，并以 CAS 保存完整性事实。
     *
     * 锁序为 control → generation ID 升序 → dirty。dirty 非空或 source 尾部变化属于前置拒绝，保持 BUILDING
     * 可继续追尾；checksum 重算或验证事实写入失败才转换为 BuildExecutionException，交由应用层失败关闭。
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun verifyBuilding(command: FeedbackListProjectionBuildCommand): FeedbackListProjectionVerificationResult {
        FeedbackListProjectionBackfillValidation.action(command.action, "VERIFY")
        val key = FeedbackListProjectionBackfillValidation.generationKey(command.generationKey)
        FeedbackListProjectionBackfillValidation.batchSize(command.batchSize)
        val epoch = controlRepository.lockControl()
        controlRepository.requireActiveActor(command.actorId)
        val generation = managedGeneration(key, "BUILDING")
        if (dirtyRepository.lockFirstDirty() != null) projectionCatchUpRequired("FEEDBACK_LIST_PROJECTION_DIRTY_NOT_EMPTY")
        requireCaughtUp(generation)
        try {
            val result = verifyLocked(generation, epoch)
            repository.markVerified(
                generation.id, epoch, result.projectedSourceCount, result.rejectedSourceCount,
                result.contentChecksum, result.memberChecksum, result.analysisChecksum,
                result.analysisIdentityChecksum, result.analysisIdentityCount
            )
            return result
        } catch (error: Exception) {
            logger.error(
                "Feedback projection verification failed: generationId={}, errorType={}",
                generation.id,
                error.javaClass.name,
                error
            )
            throw ServiceException(
                ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED,
                "FEEDBACK_LIST_PROJECTION_BUILD_EXECUTION_FAILED",
                cause = error
            )
        }
    }

    /**
     * 原子地把已验证 BUILDING 切换为 ACTIVE，并把原 ACTIVE 准备为唯一 ROLLBACK_READY。
     *
     * 在 control → generation ID 升序 → dirty 锁序下重新确认 epoch、source 尾部和候选 checksum；旧 ACTIVE
     * 也按候选 fence 重算并保存回滚验证事实。既有 ROLLBACK_READY 先退役，随后两个槽位用 affected-row CAS
     * 切换；任一检查或写入失败时整个事务回滚，公开读取不会看到半切换状态。
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun activateVerified(command: FeedbackListProjectionActivationCommand): FeedbackListProjectionActivationResult {
        FeedbackListProjectionBackfillValidation.action(command.action, "ACTIVATE")
        val key = FeedbackListProjectionBackfillValidation.generationKey(command.generationKey)
        val epoch = controlRepository.lockControl()
        controlRepository.requireActiveActor(command.actorId)
        val generations = repository.lockManagedGenerations()
        val building = generations.singleOrNull { it.generationStatus == "BUILDING" && it.generationKey == key }
            ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_VERIFIED_BUILDING_NOT_FOUND")
        if (dirtyRepository.lockFirstDirty() != null) projectionCatchUpRequired("FEEDBACK_LIST_PROJECTION_DIRTY_NOT_EMPTY")
        requireCaughtUp(building)
        if (building.verifiedAt == null || building.verifiedMutationEpoch != epoch) projectionCatchUpRequired("FEEDBACK_LIST_PROJECTION_VERIFICATION_EPOCH_CHANGED")
        assertVerificationUnchanged(building, verifyLocked(building, epoch))

        generations.singleOrNull { it.generationStatus == "ROLLBACK_READY" }?.let { previousRollback ->
            repository.transition(previousRollback.id, "ROLLBACK_READY", "RETIRED")
        }
        val previousActive = generations.singleOrNull { it.generationStatus == "ACTIVE" }
        previousActive?.let { active ->
            val fence = building.buildFenceSourceId ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_FENCE_MISSING")
            val rollbackVerification = verifier.verify(active.id, fence, building.expectedSourceCount, epoch)
            repository.prepareActiveForRollback(
                active.id, fence, building.expectedSourceCount,
                FeedbackListProjectionVerifiedFacts(
                    rollbackVerification.mutationEpoch,
                    rollbackVerification.projectedSourceCount,
                    rollbackVerification.rejectedSourceCount,
                    rollbackVerification.contentChecksum,
                    rollbackVerification.memberChecksum,
                    rollbackVerification.analysisChecksum,
                    rollbackVerification.analysisIdentityChecksum,
                    rollbackVerification.analysisIdentityCount
                )
            )
            repository.transition(active.id, "ACTIVE", "ROLLBACK_READY", cutoverEpoch = epoch)
        }
        repository.transition(building.id, "BUILDING", "ACTIVE", command.actorId, epoch)
        return FeedbackListProjectionActivationResult(building.id, "ACTIVE", previousActive?.id, epoch)
    }

    /**
     * 在独立事务中将仍为 BUILDING 的 generation 关闭为 FAILED。
     *
     * 失败码和摘要先去空格、限长并拒绝控制字符/未批准字符，再按 control → generation 锁序执行 CAS；不会把
     * 原始异常、堆栈或秘密写入 generation。若槽位已离开 BUILDING，CAS 失败而不覆盖新状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    fun failBuilding(generationKey: String, failureCode: String, failureSummary: String) {
        val key = FeedbackListProjectionBackfillValidation.generationKey(generationKey)
        val code = sanitizedFailure(failureCode, 80)
        val summary = sanitizedFailure(failureSummary, 500)
        controlRepository.lockControl()
        val generation = managedGeneration(key, "BUILDING")
        repository.transition(generation.id, "BUILDING", "FAILED", failureCode = code, failureSummary = summary)
    }

    /**
     * 原子地用 ROLLBACK_READY 替换当前 ACTIVE。
     *
     * 两个 generation 的 cutover epoch 必须仍等于锁定的 control epoch，dirty 必须为空，且双方 checksum 都要
     * 重新核验不变。当前 ACTIVE 以固定失败事实关闭，回滚候选再用 CAS 激活；任何失败都会回滚两次状态变化。
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun rollback(command: FeedbackListProjectionRollbackCommand): FeedbackListProjectionRollbackResult {
        FeedbackListProjectionBackfillValidation.action(command.action, "ROLLBACK")
        val key = FeedbackListProjectionBackfillValidation.generationKey(command.generationKey)
        val epoch = controlRepository.lockControl()
        controlRepository.requireActiveActor(command.actorId)
        val generations = repository.lockManagedGenerations()
        val rollbackReady = generations.singleOrNull { it.generationStatus == "ROLLBACK_READY" && it.generationKey == key }
            ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_ROLLBACK_READY_NOT_FOUND")
        val active = generations.singleOrNull { it.generationStatus == "ACTIVE" }
            ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_ACTIVE_NOT_FOUND")
        if (dirtyRepository.lockFirstDirty() != null) projectionCatchUpRequired("FEEDBACK_LIST_PROJECTION_DIRTY_NOT_EMPTY")
        if (active.cutoverMutationEpoch != epoch || rollbackReady.cutoverMutationEpoch != epoch) projectionCatchUpRequired("FEEDBACK_LIST_PROJECTION_ROLLBACK_EPOCH_CHANGED")
        assertVerificationUnchanged(active, verifyLocked(active, epoch))
        assertVerificationUnchanged(rollbackReady, verifyLocked(rollbackReady, epoch))
        repository.transition(
            active.id, "ACTIVE", "FAILED", failureCode = "FEEDBACK_LIST_PROJECTION_ROLLED_BACK",
            failureSummary = "Generation replaced by governed rollback"
        )
        repository.transition(rollbackReady.id, "ROLLBACK_READY", "ACTIVE", command.actorId, epoch)
        return FeedbackListProjectionRollbackResult(rollbackReady.id, "ACTIVE", active.id, epoch)
    }

    /**
     * READ_COMMITTED 下用 SKIP LOCKED 领取一个有界 dirty 租约；空返回表示本轮没有可处理记录。
     *
     * REQUIRES_NEW 是本方法的契约，不是优化：调用方
     * [FeedbackListProjectionRefreshWorker.afterDirtyCommitted] 位于
     * `@TransactionalEventListener(AFTER_COMMIT)`，那时外层写入事务已提交、连接却仍绑定在当前线程。
     * 默认传播会让领取挂进那个已经结束的事务——它自己永远不提交，行锁要等连接被回收才释放，
     * 而 [acknowledgeDirty] 等确认动作是 REQUIRES_NEW，会另开连接去等这些锁，直到
     * `innodb_lock_wait_timeout` 才失败。独立事务才符合「领取事务保持短小」的既有设计。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    fun claimDirty(preferredSourceIds: List<Long>?, limit: Int, leaseOwner: String, leaseSeconds: Long): FeedbackListProjectionDirtyClaim? =
        dirtyRepository.claim(preferredSourceIds, limit, leaseOwner, leaseSeconds)

    /**
     * 在同一 REPEATABLE_READ 事务中刷新所有可写 generation；ACTIVE 总是处理，BUILDING 只处理 fence 内 ID，
     * fence 外变更留给构建 keyset，避免 cursor 语义被增量写穿。
     *
     * 传播口径与 [claimDirty] 相同：投影写入必须先于确认独立提交。理由见该方法的说明。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    fun refreshIncremental(command: FeedbackListProjectionIncrementalRefreshCommand): FeedbackListProjectionIncrementalRefreshResult =
        synchronizer.refreshIncremental(command)

    /** 仅按 lease owner 与 mutation version 删除已处理行；并发产生的新版本不会被旧 Worker 确认。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun acknowledgeDirty(claim: FeedbackListProjectionDirtyClaim): Int = dirtyRepository.acknowledge(claim)

    /** 释放当前版本租约并延后重领，不增加重试次数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferDirty(claim: FeedbackListProjectionDirtyClaim, delaySeconds: Long): Int = dirtyRepository.release(claim, delaySeconds)

    /** 记录受限错误事实、增加重试次数并安排下一次领取；旧版本更新不到并发产生的新 dirty。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retryDirty(claim: FeedbackListProjectionDirtyClaim, delaySeconds: Long, errorCode: String, errorSummary: String): Int =
        dirtyRepository.retry(claim, delaySeconds, errorCode, errorSummary)

    /** 将当前租约版本标记为最终失败并清除租约；不删除 dirty 恢复事实。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failDirty(claim: FeedbackListProjectionDirtyClaim, errorCode: String, errorSummary: String): Int =
        dirtyRepository.fail(claim, errorCode, errorSummary)

    private fun managedGeneration(key: String, status: String): FeedbackListProjectionGenerationPo =
        repository.lockManagedGenerations().singleOrNull { it.generationKey == key && it.generationStatus == status }
            ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_GENERATION_STATE_INVALID")

    private fun verifyLocked(generation: FeedbackListProjectionGenerationPo, epoch: Long): FeedbackListProjectionVerificationResult {
        val fence = generation.buildFenceSourceId ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_FENCE_MISSING")
        if ((generation.buildCursorSourceId ?: 0L) != fence) projectionCatchUpRequired("FEEDBACK_LIST_PROJECTION_BUILD_INCOMPLETE")
        return verifier.verify(generation.id, fence, generation.expectedSourceCount, epoch)
    }

    /** control、generation、dirty 已按全局顺序锁定后，确认在线 source 尾部已被候选 generation 追平。 */
    private fun requireCaughtUp(generation: FeedbackListProjectionGenerationPo) {
        val fence = generation.buildFenceSourceId ?: catchUpRequired()
        val cursor = generation.buildCursorSourceId ?: 0L
        if (cursor != fence) catchUpRequired()
        val currentFence = repository.sourceFence()
        val currentCount = repository.sourceCountThroughFence(currentFence)
        if (currentFence != fence || currentCount != generation.expectedSourceCount) {
            catchUpRequired()
        }
    }

    private fun catchUpRequired(): Nothing = throw ServiceException(
        ErrorCode.FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED,
        "FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED"
    )

    private fun assertVerificationUnchanged(generation: FeedbackListProjectionGenerationPo, result: FeedbackListProjectionVerificationResult) {
        if (!(generation.projectedSourceCount == result.projectedSourceCount &&
            generation.rejectedSourceCount == result.rejectedSourceCount &&
            generation.contentChecksum == result.contentChecksum &&
            generation.verifiedMemberChecksum == result.memberChecksum &&
            generation.analysisProjectionVersion == FeedbackListProjectionRepository.ANALYSIS_PROJECTION_VERSION &&
            generation.verifiedAnalysisChecksum == result.analysisChecksum &&
            generation.verifiedAnalysisIdentityChecksum == result.analysisIdentityChecksum &&
            generation.projectedAnalysisIdentityCount == result.analysisIdentityCount)) {
            projectionCatchUpRequired("FEEDBACK_LIST_PROJECTION_VERIFICATION_CHANGED")
        }
    }

    private fun sanitizedFailure(value: String, maxLength: Int): String = value.trim().take(maxLength).also {
        if (it.isEmpty() || it.any(Char::isISOControl) || !it.matches(Regex("[A-Za-z0-9 _;.-]+"))) {
            invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_FAILURE_INVALID")
        }
    }
}
