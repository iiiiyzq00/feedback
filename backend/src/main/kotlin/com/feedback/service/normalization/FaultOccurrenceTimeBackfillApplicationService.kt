package com.feedback.service.normalization

import com.feedback.model.po.normalization.FaultOccurrenceTimeSourcePo
import com.feedback.repository.normalization.FaultOccurrenceTimeRepository
import com.feedback.repository.normalization.FaultOccurrenceTimeUpdate
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class FaultOccurrenceTimeBatchResult(
    val updatedCount: Int,
    val nextSourceId: Long,
    val candidateCount: Int
) {
    companion object {
        fun empty(cursor: Long) = FaultOccurrenceTimeBatchResult(0, cursor, 0)
    }
}

/**
 * 在一个独立权威事务中解析并更新一批来源故障时间。
 *
 * source 行先按 ID 锁定；五张表各最多读取一次。source 更新与 SOURCE 脏标记必须共同提交，任一失败均向外传播。
 */
@Service
class FaultOccurrenceTimeBackfillApplicationService(
    private val repository: FaultOccurrenceTimeRepository,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processBatch(afterSourceId: Long): FaultOccurrenceTimeBatchResult {
        val sources = repository.findPendingAfter(afterSourceId, FaultOccurrenceTimeRepository.MAX_BATCH_SIZE)
        if (sources.isEmpty()) return FaultOccurrenceTimeBatchResult.empty(afterSourceId)

        val sourceIds = sources.map(FaultOccurrenceTimeSourcePo::id)
        val contents = repository.contents(sourceIds)
        // 旧实现只处理同时存在 content 的来源；缺失安全正文的 source 必须继续保持 PENDING。
        val eligibleSources = sources.filter { contents.containsKey(it.id) }
        val jiraIds = eligibleSources.idsFor("JIRA")
        val stationIds = eligibleSources.idsFor("STATION_FEEDBACK")
        val qualityIds = eligibleSources.idsFor("QUALITY")
        val jira = repository.jira(jiraIds)
        val station = repository.station(stationIds)
        val quality = repository.quality(qualityIds)

        val updates = eligibleSources.map { source ->
            val description = contents[source.id]?.description
            val fact = when (source.sourceChannel) {
                "SDK" -> FaultOccurrenceTimeResolver.sdk(source.sourceEventTime)
                "REDBLACK" -> FaultOccurrenceTimeResolver.notApplicable()
                "JIRA" -> FaultOccurrenceTimeResolver.fromDescription(
                    source.sourceChannel,
                    description,
                    jira[source.id]?.jiraCreatedAt ?: source.sourceEventTime
                )
                "STATION_FEEDBACK" -> FaultOccurrenceTimeResolver.fromDescription(
                    source.sourceChannel,
                    listOfNotNull(description, station[source.id]?.sourceDescription).joinToString("\n"),
                    source.sourceEventTime
                )
                "QUALITY" -> FaultOccurrenceTimeResolver.fromDescription(
                    source.sourceChannel,
                    listOfNotNull(description, quality[source.id]?.faultConfirmed, quality[source.id]?.faultRemark)
                        .joinToString("\n"),
                    source.sourceEventTime
                )
                else -> FaultTimeFact(null, null, null, "${source.sourceChannel}_UNSUPPORTED", "MISSING")
            }
            FaultOccurrenceTimeUpdate(
                source.id,
                fact.occurredAt,
                fact.raw,
                fact.precision,
                fact.source,
                fact.resolution
            )
        }

        if (updates.isNotEmpty()) {
            repository.updateAll(updates)
            dirtyMarker.mark(
                FeedbackListProjectionDirtyCommand(
                    updates.map(FaultOccurrenceTimeUpdate::sourceId),
                    FeedbackListProjectionDirtyReason.SOURCE
                )
            )
        }
        return FaultOccurrenceTimeBatchResult(updates.size, sources.last().id, sources.size)
    }

    private fun List<FaultOccurrenceTimeSourcePo>.idsFor(channel: String): List<Long> =
        filter { it.sourceChannel == channel }.map(FaultOccurrenceTimeSourcePo::id)
}
