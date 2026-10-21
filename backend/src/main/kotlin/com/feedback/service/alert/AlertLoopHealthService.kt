package com.feedback.service.alert

import com.feedback.repository.alert.AlertLoopHealthRepository
import org.springframework.stereotype.Service

/** 规则 -> 告警事件 -> 通知 -> 恢复 这条闭环的只读运维证据。 */
@Service
class AlertLoopHealthService(private val repository: AlertLoopHealthRepository) {
    fun health(): Map<String, Any?> {
        val coverage = repository.coverage().associateBy { it.category }
        val required = linkedMapOf(
            "DATA_INGEST" to "同步与接入失败",
            "DATA_QUALITY" to "数据质量异常",
            "PRODUCT_TREND" to "热点与潜在舆情",
            "MASKING" to "隐私处理异常"
        )
        val categories = required.map { (code, label) ->
            val row = coverage[code]
            mapOf("code" to code, "label" to label,
                "ruleCount" to (row?.ruleCount ?: 0),
                "activeCount" to (row?.activeCount ?: 0),
                "covered" to ((row?.activeCount ?: 0) > 0))
        }
        val r=repository.runtime();val runtime=linkedMapOf<String,Any?>("lastEvaluationAt" to r.lastEvaluationAt,
            "openAlerts" to r.openAlerts,"recovered24h" to r.recovered24h,"pendingNotifications" to r.pendingNotifications,
            "deadLetters" to r.deadLetters,"lastDeliveryAt" to r.lastDeliveryAt)
        val covered = categories.all { it["covered"] == true }
        val deadLetters = r.deadLetters
        val status = when {
            !covered -> "NOT_READY"
            deadLetters > 0 -> "DEGRADED"
            else -> "HEALTHY"
        }
        return linkedMapOf("status" to status, "checkedAt" to java.time.LocalDateTime.now(),
            "categories" to categories, "runtime" to runtime,
            "closureDefinition" to "规则启用→评估快照→告警实例→通知/回执→恢复或人工关闭")
    }
}
