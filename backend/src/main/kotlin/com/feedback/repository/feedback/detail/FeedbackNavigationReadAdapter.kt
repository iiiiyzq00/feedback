package com.feedback.repository.feedback.detail

import com.feedback.application.port.feedback.detail.*
import com.feedback.model.po.feedback.detail.FeedbackNavigationSourcePo
import org.springframework.stereotype.Repository

/** 导航持久化适配器；把可变 MyBatis 行限制在基础设施层。 */
@Repository
class FeedbackNavigationReadRepository(
    private val repository: FeedbackDetailRepository
) : FeedbackNavigationReadPort {
    override fun previousCandidates(
        cursor: FeedbackNavigationCursorSnapshot,
        channels: Set<String>,
        regions: Set<String>?,
        limit: Int
    ) = repository.previousCandidates(cursor.toPo(), channels, regions, limit).map { it.toSnapshot() }

    override fun nextCandidates(
        cursor: FeedbackNavigationCursorSnapshot,
        channels: Set<String>,
        regions: Set<String>?,
        limit: Int
    ) = repository.nextCandidates(cursor.toPo(), channels, regions, limit).map { it.toSnapshot() }

    override fun content(sourceIds: List<Long>): Map<Long, FeedbackNavigationContentSnapshot> =
        repository.navigationContent(sourceIds).associate { row ->
            row.sourceId to FeedbackNavigationContentSnapshot(
                sourceId = row.sourceId,
                functionModule = row.functionModule,
                maskingStatus = row.maskingStatus,
                deleted = row.deleted
            )
        }

    private fun FeedbackNavigationCursorSnapshot.toPo() = FeedbackNavigationSourcePo(
        id = id,
        sourceChannel = sourceChannel,
        provinceCode = provinceCode,
        cityCode = cityCode,
        faultOccurredAt = faultOccurredAt
    )

    private fun FeedbackNavigationSourcePo.toSnapshot() = FeedbackNavigationCursorSnapshot(
        id = id,
        sourceChannel = sourceChannel,
        provinceCode = provinceCode,
        cityCode = cityCode,
        faultOccurredAt = faultOccurredAt
    )
}
