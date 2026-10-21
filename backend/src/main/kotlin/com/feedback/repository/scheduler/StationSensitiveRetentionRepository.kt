package com.feedback.repository.scheduler

import com.feedback.mapper.scheduler.MaintenanceFeedbackContentMapper
import com.feedback.mapper.scheduler.MaintenanceStationRawMapper
import com.feedback.model.po.scheduler.MaintenanceStationCipherCandidatePo
import org.springframework.stereotype.Repository

/** Station 原文密文候选、安全状态和清理。 */
@Repository
class StationSensitiveRetentionRepository(
    private val station: MaintenanceStationRawMapper,
    private val contents: MaintenanceFeedbackContentMapper
) {
    fun candidates(afterId: Long?, limit: Int): List<MaintenanceStationCipherCandidatePo> = station.candidates(afterId, limit)
    fun maskingStatuses(sourceIds: Collection<Long>) = sourceIds.chunked(RETENTION_BATCH_SIZE)
        .flatMap(contents::maskingStatuses).associate { it.sourceId to it.maskingStatus }
    fun clear(ids: Collection<Long>): Int = ids.chunked(RETENTION_BATCH_SIZE).sumOf(station::clear)
}
