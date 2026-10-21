package com.feedback.repository.scheduler

import com.feedback.mapper.scheduler.MaintenanceAlertInstanceMapper
import com.feedback.mapper.scheduler.MaintenanceSdkCipherMapper
import com.feedback.model.po.scheduler.MaintenanceSdkCipherCandidatePo
import org.springframework.stereotype.Repository

/** SDK 密文候选、活动告警保护和清理。 */
@Repository
class SdkSensitiveRetentionRepository(
    private val sdk: MaintenanceSdkCipherMapper,
    private val alerts: MaintenanceAlertInstanceMapper
) {
    fun candidates(afterId: Long?, limit: Int): List<MaintenanceSdkCipherCandidatePo> = sdk.candidates(afterId, limit)
    fun activeAlertSourceIds(sourceIds: Collection<Long>): Set<Long> = when {
        sourceIds.isEmpty() -> emptySet()
        else -> sourceIds.chunked(RETENTION_BATCH_SIZE).flatMap(alerts::activeFeedbackSourceIds).toSet()
    }
    fun clear(ids: Collection<Long>): Int = ids.chunked(RETENTION_BATCH_SIZE).sumOf(sdk::clear)
}

internal const val RETENTION_BATCH_SIZE = 500
