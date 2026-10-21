package com.feedback.service.sync

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.repository.sync.IndexConsistencyRepository
import com.feedback.service.cluster.ClusterStatisticsService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class IndexConsistencyAdminService(
    private val repository: IndexConsistencyRepository,
    private val statistics: ClusterStatisticsService,
    private val worker: DerivedIndexSyncWorker,
    private val meters: MeterRegistry,
    private val mapper: ObjectMapper
) {
    private val pendingGauge=java.util.concurrent.atomic.AtomicLong().also{meters.gauge("feedback_index_pending_records",it)}
    private val driftGauge=java.util.concurrent.atomic.AtomicLong().also{meters.gauge("feedback_cluster_statistics_drift",it)}
    fun status(): Map<String,Any> {
        val outbox=repository.outboxStatus()
        val feedbackPending=repository.feedbackPending()
        val clusterPending=repository.clusterPending()
        val drift=statistics.findDrift(500).size
        pendingGauge.set(feedbackPending+clusterPending);driftGauge.set(drift.toLong())
        return mapOf("outbox" to outbox,"feedbackPending" to feedbackPending,"clusterPending" to clusterPending,
            "statisticsDrift" to drift,"consistency" to if(drift==0&&feedbackPending==0L&&clusterPending==0L)"CONSISTENT" else "RECONCILING")
    }

    @Transactional
    fun replay(taskId:Long, reason:String, operatorId:Long) {
        if(reason.trim().length !in 3..500) invalid("INDEX_REPLAY_REASON_INVALID")
        val normalizedReason=reason.trim()
        if(!repository.replay(taskId,normalizedReason)) invalid("INDEX_OUTBOX_NOT_REPLAYABLE")
        repository.audit(operatorId,"INDEX_OUTBOX_REPLAY","INDEX_OUTBOX",taskId.toString(),
            mapper.writeValueAsString(mapOf("reason" to normalizedReason)))
    }

    @Transactional
    fun reconcileStatistics(operatorId:Long):Map<String,Any> {
        val drift=statistics.findDrift(5000); statistics.recompute(drift.map{it.clusterId})
        repository.audit(operatorId,"CLUSTER_STATISTICS_MANUAL_RECONCILE","CLUSTER_STATISTICS",null,
            mapper.writeValueAsString(mapOf("driftCount" to drift.size,"clusterIds" to drift.map{it.clusterId}.joinToString(","))))
        worker.seedOutbox()
        return mapOf("repaired" to drift.size,"clusterIds" to drift.map{it.clusterId})
    }

    private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
}
