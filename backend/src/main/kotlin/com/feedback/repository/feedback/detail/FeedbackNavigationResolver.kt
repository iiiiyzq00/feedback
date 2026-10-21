package com.feedback.repository.feedback.detail

import com.feedback.application.port.feedback.detail.*
import com.feedback.common.ErrorCode
import com.feedback.common.FeedbackChannel
import com.feedback.common.exception.ServiceException
import com.feedback.model.auth.DataAccessScope
import org.springframework.stereotype.Component

/**
 * 以 200 条单表 keyset 候选为一批解析相邻反馈，再用一次正文批量读取应用模块范围。
 * 稀疏范围继续读取下一批，但每个方向最多读取 1,000 个候选；耗尽预算时失败关闭，
 * 避免把尚未扫描的数据集边界误报为不存在相邻记录，也避免全量加载和逐条查询。
 */
@Component("feedbackNavigationResolver")
class FeedbackNavigationResolver(private val reads: FeedbackNavigationReadPort) {
    fun resolve(
        source: FeedbackSourceSnapshot,
        scope: DataAccessScope
    ): FeedbackNavigationSnapshot {
        val cursor = FeedbackNavigationCursorSnapshot(
            id = source.id,
            sourceChannel = source.sourceChannel,
            provinceCode = source.provinceCode,
            cityCode = source.cityCode,
            faultOccurredAt = source.faultOccurredAt
        )
        val channels = scope.channels.intersect(FeedbackChannel.feedbackWireValues)
        return FeedbackNavigationSnapshot(
            previousId = findVisibleNeighbour(cursor, channels, scope, previous = true),
            nextId = findVisibleNeighbour(cursor, channels, scope, previous = false)
        )
    }

    private fun findVisibleNeighbour(
        initial: FeedbackNavigationCursorSnapshot,
        channels: Set<String>,
        scope: DataAccessScope,
        previous: Boolean
    ): Long? {
        var cursor = initial
        var candidateCount = 0
        repeat(MAX_BATCHES_PER_DIRECTION) {
            val candidates = if (previous) {
                reads.previousCandidates(cursor, channels, scope.regions, BATCH_SIZE)
            } else {
                reads.nextCandidates(cursor, channels, scope.regions, BATCH_SIZE)
            }
            if (candidates.isEmpty()) return null
            val contentById = reads.content(candidates.map(FeedbackNavigationCursorSnapshot::id))
            candidates.firstOrNull { candidate ->
                val content = contentById[candidate.id] ?: return@firstOrNull false
                !content.deleted && content.maskingStatus == "PASS" && scope.permits(
                    candidate.sourceChannel,
                    content.functionModule,
                    candidate.provinceCode,
                    candidate.cityCode
                )
            }?.let { return it.id }
            if (candidates.size < BATCH_SIZE) return null
            candidateCount += candidates.size
            cursor = candidates.last()
        }
        // 满批只能证明当前预算内没有可见项，不能证明已到达数据集边界。
        throw ServiceException(
            ErrorCode.FEEDBACK_READ_UNAVAILABLE,
            NAVIGATION_SCAN_BUDGET_EXHAUSTED,
            mapOf("candidateBudgetPerDirection" to candidateCount)
        )
    }

    private companion object {
        const val BATCH_SIZE = 200
        const val MAX_BATCHES_PER_DIRECTION = 5
        const val NAVIGATION_SCAN_BUDGET_EXHAUSTED = "FEEDBACK_NAVIGATION_SCAN_BUDGET_EXHAUSTED"
    }
}
