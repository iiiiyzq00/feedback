package com.feedback.application.port.problem.analysis

import java.math.BigDecimal
import java.time.LocalDateTime

data class RelatedFeedbackReadRequest(
    val generationId: Long,
    val scope: RelatedFeedbackScope,
    val sourceId: Long,
    val page: Int,
    val size: Int
)

data class RelatedFeedbackScope(
    val allowedChannels: List<String>,
    val modules: List<String>?,
    val regions: List<String>?
)

/** 同簇反馈读取合同；实现必须批量补齐本页安全投影，缺失任一投影时整体失败。 */
interface RelatedFeedbackReadPort {
    fun read(request: RelatedFeedbackReadRequest): RelatedFeedbackSnapshot
}

data class RelatedFeedbackSnapshot(val items: List<RelatedFeedbackItemSnapshot>, val total: Long)

data class RelatedFeedbackItemSnapshot(
    val sourceId: Long,
    val clusterId: Long,
    val sourceChannel: String,
    val sourceRecordId: String?,
    val title: String?,
    val description: String?,
    val reportedAt: LocalDateTime?,
    val matchSimilarity: BigDecimal?,
    val decisionReason: String?
)
