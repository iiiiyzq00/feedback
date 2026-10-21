package com.feedback.service.alert

import com.feedback.repository.alert.AlertReviewGovernanceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Value
import java.time.LocalDateTime

@Service
class AlertReviewGovernanceMetricService(
    private val repository: AlertReviewGovernanceRepository,
    @Value("\${app.review-governance.sla-enabled:false}") private val slaEnabled: Boolean=false
) {
    fun clusterSlaBreaches(at: LocalDateTime = LocalDateTime.now()): Double = if(!slaEnabled) 0.0 else repository.clusterBreaches(at)

    fun maskingSlaBreaches(at: LocalDateTime = LocalDateTime.now()): Double {
        if(!slaEnabled) return 0.0
        return repository.maskingBreaches(at)
    }

    fun reviewDecisionCount(at: LocalDateTime = LocalDateTime.now()) = repository.decisionCount(at)

    fun manualOverturnRate(at: LocalDateTime = LocalDateTime.now()) = repository.overturnRate(at)

    fun ruleInputCompleteness(at: LocalDateTime = LocalDateTime.now()): Double = repository.inputCompleteness(at)

    @Transactional
    fun snapshotContractInputs() {
        val end = LocalDateTime.now().withSecond(0).withNano(0)
        val start = end.minusMinutes(5)
        repository.snapshot(start,end)
    }
}
