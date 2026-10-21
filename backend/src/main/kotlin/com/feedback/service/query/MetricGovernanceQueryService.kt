package com.feedback.service.query

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.service.auth.DataPermissionService
import com.feedback.repository.query.MetricGovernanceRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class MetricGovernanceQueryService(
    private val repository:MetricGovernanceRepository,
    private val permissions:DataPermissionService
){
    fun definitions():List<Map<String,Any?>> = repository.definitions().map { row -> linkedMapOf(
        "metricCode" to row.metricCode,"displayName" to row.displayName,"ownerModule" to row.ownerModule,
        "entityType" to row.entityType,"distinctKey" to row.distinctKey,"timeField" to row.timeField,
        "defaultWindow" to row.defaultWindow,"definition" to row.definition,"drilldownPath" to row.drilldownPath,
        "metricVersion" to row.metricVersion,"updatedAt" to row.updatedAt) }

    fun reconciliation(range:String="today"):Map<String,Any>{
        if (range !in ProcessingChainTimeRange.supported) {
            throw ClientException(ErrorCode.BAD_REQUEST, "METRIC_RECONCILIATION_RANGE_INVALID")
        }
        val end=LocalDateTime.now();val start=ProcessingChainTimeRange.start(range,end);val scope=permissions.scope()
        val row=repository.reconciliation(scope,start).asMap()
        fun n(key:String)=(row[key] as? Number)?.toLong()?:0L
        val received=n("received");val pass=n("privacyPass");val privacyAccounted=pass+n("reviewNeeded")+n("failed")+n("pending")
        val ai=n("aiProcessed");val aiAccounted=n("aiAutoAccepted")+n("aiReviewPending")+n("aiReviewed")+n("aiOverruled")
        val cluster=n("clusterDecided");val clusterAccounted=n("linkedExisting")+n("newCluster")
        val checks=listOf(
            check("PRIVACY_PASS_LE_RECEIVED",pass,received,pass<=received),
            check("PRIVACY_STATES_RECONCILE_RECEIVED",privacyAccounted,received,privacyAccounted==received),
            check("AI_PROCESSED_LE_PRIVACY_PASS",ai,pass,ai<=pass),
            check("AI_OUTCOMES_RECONCILE_PROCESSED",aiAccounted,ai,aiAccounted==ai),
            check("CLUSTER_DECIDED_LE_AI_PROCESSED",cluster,ai,cluster<=ai),
            check("CLUSTER_OUTCOMES_RECONCILE_DECIDED",clusterAccounted,cluster,clusterAccounted==cluster)
        )
        return linkedMapOf("status" to if(checks.all{it["passed"]==true})"PASSED" else "FAILED","checks" to checks,"counts" to row,
            "range" to range,"startTime" to start,"endTime" to end,"metricVersion" to "metric-reconciliation-v3")
    }

    private fun check(code:String,left:Long,right:Long,passed:Boolean)=mapOf("check" to code,"passed" to passed,"left" to left,"right" to right)
}
