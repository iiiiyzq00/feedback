package com.feedback.service.query.listprojection

import com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class FeedbackListProjectionRefreshCommand(
    val generationId: Long,
    val sourceIds: List<Long>
)

data class FeedbackListProjectionRefreshResult(
    val generationId: Long,
    val requestedCount: Int,
    val projectedCount: Int,
    val rejectedCount: Int,
    val cursorSourceId: Long
)

data class FeedbackListProjectionIncrementalRefreshCommand(val sourceIds: List<Long>)

data class FeedbackListProjectionIncrementalRefreshResult(
    val requestedCount: Int,
    val projectedCount: Int,
    val rejectedCount: Int,
    val handledGenerationIds: List<Long>,
    val deferredToBuildKeysetCount: Int = 0
) {
    val hasWritableGeneration: Boolean get() = handledGenerationIds.isNotEmpty()
}

/**
 * 反馈列表投影的 BUILDING 全量批次与已提交 dirty 增量同步器。
 *
 * 全量批次固定执行：锁 BUILDING → 读取 source/content → 安全资格失败关闭 → 仅为合格 ID
 * 批量读取辅助事实 → 替换投影 → 推进游标。增量批次锁定 ACTIVE/BUILDING 后按 generation 分流：
 * ACTIVE 接收全部 ID，BUILDING 只接收当前 fence 内 ID，fence 外 ID 由后续 source keyset catch-up。
 * 任一持久化异常由外层事务整体回滚；激活必须依赖全量数量、source 集合与 checksum 复核，不能用
 * 局部时间戳推断附件删除、交接取消、安全状态或渠道事实已经收敛。
 */
@Component
class FeedbackListProjectionSynchronizer(
    private val repository: FeedbackListProjectionRepository,
    private val assembler: FeedbackListProjectionAssembler
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun refresh(command: FeedbackListProjectionRefreshCommand): FeedbackListProjectionRefreshResult {
        if (command.generationId <= 0) invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_GENERATION_ID_INVALID")
        validateSourceIds(command.sourceIds)
        val ids = command.sourceIds.sorted()
        val generation = repository.lockBuilding(command.generationId)
        generation.buildCursorSourceId?.let { cursor ->
            if (ids.first() <= cursor) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_CURSOR_NOT_MONOTONIC")
        }

        val sourceById = repository.sources(ids)
        val contentById = repository.contents(ids)
        val eligibleSources = ids.mapNotNull { id ->
            sourceById[id]?.takeIf { assembler.isSafelyEligible(it, contentById[id]) }
        }
        // 安全边界：在前置资格校验完成之前，不调用任何辅助 Mapper。
        val auxiliary = repository.auxiliary(eligibleSources)
        val snapshots = eligibleSources.map { source ->
            assembler.assemble(command.generationId, source, contentById.getValue(source.id), auxiliary)
        }
        repository.replaceBatch(
            generationId = command.generationId,
            requestedSourceIds = ids,
            projections = snapshots.map(FeedbackListProjectionSnapshot::projection),
            members = snapshots.mapNotNull(FeedbackListProjectionSnapshot::clusterMember),
            identities = snapshots.flatMap(FeedbackListProjectionSnapshot::clusterIdentities)
        )
        val rejected = ids.size - snapshots.size
        repository.advanceBuild(command.generationId, ids.last(), snapshots.size.toLong(), rejected.toLong())
        return FeedbackListProjectionRefreshResult(
            generationId = command.generationId,
            requestedCount = ids.size,
            projectedCount = snapshots.size,
            rejectedCount = rejected,
            cursorSourceId = ids.last()
        )
    }

    /**
     * 将一批已提交权威变更刷新到当前 BUILDING/ACTIVE generation。
     *
     * 增量刷新不推进构建游标或累计计数；任何激活前都必须重新执行全量数量和内容校验。
     * ACTIVE 接收全部 ID；BUILDING 只接收当前验证 fence 内 ID，fence 外 ID 由后续 source keyset
     * catch-up 确定性覆盖。若来源缺失或不再满足安全资格，会从对应 generation 删除原投影。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun refreshIncremental(
        command: FeedbackListProjectionIncrementalRefreshCommand
    ): FeedbackListProjectionIncrementalRefreshResult {
        val ids = normalizedSourceIds(command.sourceIds)
        val generations = repository.lockWritableGenerations()
        if (generations.isEmpty()) {
            return FeedbackListProjectionIncrementalRefreshResult(ids.size, 0, ids.size, emptyList())
        }
        val building = generations.singleOrNull { it.generationStatus == "BUILDING" }
        val buildingFence = building?.buildFenceSourceId
            ?: building?.let { projectionOperationFailed("FEEDBACK_LIST_PROJECTION_FENCE_MISSING") }
        val immediateIds = generations.flatMap { generation ->
            when (generation.generationStatus) {
                "ACTIVE" -> ids
                "BUILDING" -> ids.filter { it <= (buildingFence ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_FENCE_MISSING")) }
                else -> projectionOperationFailed("FEEDBACK_LIST_PROJECTION_WRITABLE_STATUS_INVALID")
            }
        }.distinct().sorted()
        if (immediateIds.isEmpty()) {
            return FeedbackListProjectionIncrementalRefreshResult(
                requestedCount = ids.size,
                projectedCount = 0,
                rejectedCount = 0,
                handledGenerationIds = generations.map { it.id },
                deferredToBuildKeysetCount = ids.size
            )
        }
        val sourceById = repository.sources(immediateIds)
        val contentById = repository.contents(immediateIds)
        val eligibleSources = immediateIds.mapNotNull { id ->
            sourceById[id]?.takeIf { assembler.isSafelyEligible(it, contentById[id]) }
        }
        // 安全边界：在每一项来源/内容资格校验完成之前，辅助事实一律不读取。
        val auxiliary = repository.auxiliary(eligibleSources)
        generations.forEach { generation ->
            val generationIds = when (generation.generationStatus) {
                "ACTIVE" -> ids
                "BUILDING" -> ids.filter { it <= (buildingFence ?: projectionOperationFailed("FEEDBACK_LIST_PROJECTION_FENCE_MISSING")) }
                else -> projectionOperationFailed("FEEDBACK_LIST_PROJECTION_WRITABLE_STATUS_INVALID")
            }
            if (generationIds.isEmpty()) return@forEach
            val generationIdSet = generationIds.toHashSet()
            val snapshots = eligibleSources.filter { it.id in generationIdSet }.map { source ->
                assembler.assemble(generation.id, source, contentById.getValue(source.id), auxiliary)
            }
            repository.replaceBatch(
                generationId = generation.id,
                requestedSourceIds = generationIds,
                projections = snapshots.map(FeedbackListProjectionSnapshot::projection),
                members = snapshots.mapNotNull(FeedbackListProjectionSnapshot::clusterMember),
                identities = snapshots.flatMap(FeedbackListProjectionSnapshot::clusterIdentities)
            )
        }
        return FeedbackListProjectionIncrementalRefreshResult(
            requestedCount = ids.size,
            projectedCount = eligibleSources.size,
            rejectedCount = immediateIds.size - eligibleSources.size,
            handledGenerationIds = generations.map { it.id },
            deferredToBuildKeysetCount = buildingFence?.let { fence -> ids.count { it > fence } } ?: 0
        )
    }

    private fun normalizedSourceIds(sourceIds: List<Long>): List<Long> {
        validateSourceIds(sourceIds)
        return sourceIds.sorted()
    }

    private fun validateSourceIds(sourceIds: List<Long>) {
        if (sourceIds.isEmpty()) invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_BATCH_EMPTY")
        if (sourceIds.size > FeedbackListProjectionRepository.MAX_BATCH_SIZE) invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_BATCH_TOO_LARGE")
        if (sourceIds.any { it <= 0 } || sourceIds.distinct().size != sourceIds.size) {
            invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_SOURCE_IDS_INVALID")
        }
    }
}
