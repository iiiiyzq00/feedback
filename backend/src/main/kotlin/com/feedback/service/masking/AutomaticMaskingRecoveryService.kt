package com.feedback.service.masking

import com.feedback.service.station.StationOnlineMaskingRecoveryService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.feedback.repository.masking.AutomaticMaskingRecoveryRepository

typealias MaskingClusterReconciliation = com.feedback.repository.masking.MaskingClusterReconciliation

/** 消除旧版的人工复核等待，但绝不把未脱敏原文提升为可用数据。 */
@Service
class AutomaticMaskingRecoveryService(
    private val repository: AutomaticMaskingRecoveryRepository,
    private val stationOnlineRecovery: StationOnlineMaskingRecoveryService
) {
    @Transactional
    fun requeueLegacyReviews(): Int {
        return repository.requeueLegacyReviews()
    }

    /**
     * Station Online 的 masking_status='REVIEW_NEEDED' 恢复已委托给单表 Repository 编排；
     * 技术失败仍以 MASKING_TECHNICAL_FAILURE 留在渠道状态中，Legacy/未知 sourceMode 不变。
     */
    fun reprocessStationReviews(limit: Int = 100): Int = stationOnlineRecovery.recover(limit)

    fun reconciliation(): MaskingClusterReconciliation {
        return repository.reconciliation()
    }
}

@Component
class AutomaticMaskingRecoveryScheduler(private val recovery: AutomaticMaskingRecoveryService) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun recover() {
        val queued = recovery.requeueLegacyReviews()
        val station = recovery.reprocessStationReviews()
        val counts = recovery.reconciliation()
        if (queued > 0 || station > 0 || counts.pendingClusterRecords > 0) {
            log.info(
                "Automatic masking recovery queued={} stationRecovered={} safe={} clustered={} pendingCluster={} technicalFailures={}",
                queued, station, counts.safeRecords, counts.clusteredSafeRecords,
                counts.pendingClusterRecords, counts.technicalFailures
            )
        }
    }
}
