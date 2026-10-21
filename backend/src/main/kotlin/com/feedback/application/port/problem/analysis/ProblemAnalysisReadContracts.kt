package com.feedback.application.port.problem.analysis

import java.math.BigDecimal
import java.time.LocalDateTime

data class ProblemAnalysisReadScope(
    val allowedChannels: List<String>,
    val modules: List<String>?,
    val regions: List<String>?
)

data class ProblemAnalysisReadPeriod(val startTime: LocalDateTime?, val endTime: LocalDateTime?)

data class ProblemAnalysisReadPage(val page: Int, val size: Int) {
    val offset: Long get() = (page - 1L) * size
}

data class ProblemAnalysisReadRequest(
    val generationId: Long,
    val scope: ProblemAnalysisReadScope,
    val period: ProblemAnalysisReadPeriod,
    val allClusters: ProblemAnalysisReadPage,
    val crossChannelClusters: ProblemAnalysisReadPage
)

interface ProblemAnalysisReadPort {
    fun read(request: ProblemAnalysisReadRequest): ProblemAnalysisSnapshot
}

data class ProblemAnalysisSnapshot(
    val totals: ProblemAnalysisTotalsSnapshot,
    val allClusters: List<ProblemClusterSnapshot>,
    val crossChannelClusters: List<ProblemClusterSnapshot>
)

data class ProblemAnalysisTotalsSnapshot(val allClusters: Long, val crossChannelClusters: Long)

data class ProblemClusterDimensionSnapshot(val value: String, val count: Long)

data class ProblemClusterSnapshot(
    val clusterId: Long,
    val canonicalSymptom: String?,
    val representativeDescription: String?,
    val representativeTitle: String?,
    val functionModule: String?,
    val issueCategory: String?,
    val recordCount: Long,
    val userCount: Long?,
    val deviceCount: Long,
    val applications: List<ProblemClusterDimensionSnapshot>,
    val firstOccurredAt: LocalDateTime?,
    val lastOccurredAt: LocalDateTime?,
    val attachmentCount: Long,
    val screenshotCount: Long,
    val logCount: Long,
    val regions: List<ProblemClusterDimensionSnapshot>
)

data class ClusterDetailReadRequest(
    val generationId: Long,
    val scope: ProblemAnalysisReadScope,
    val clusterId: Long,
    val period: ProblemAnalysisReadPeriod
)

data class ClusterFeedbackReadRequest(
    val generationId: Long,
    val scope: ProblemAnalysisReadScope,
    val clusterId: Long,
    val page: ProblemAnalysisReadPage,
    val period: ProblemAnalysisReadPeriod
)

interface ClusterReadPort {
    fun detail(request: ClusterDetailReadRequest): ClusterDetailSnapshot?
    fun feedback(request: ClusterFeedbackReadRequest): ClusterFeedbackSnapshot
}

data class ClusterDetailSnapshot(
    val id: Long,
    val name: String,
    val canonicalSymptom: String?,
    val representativeDescription: String?,
    val representativeTitle: String?,
    val functionModule: String?,
    val issueCategory: String?,
    val status: String,
    val lifecycleVersion: Long,
    val mergedIntoClusterId: Long?,
    val splitFromClusterId: Long?,
    val statusReason: String?,
    val recordCount: Long,
    val logicalChannelCount: Long,
    val lastOccurredAt: LocalDateTime?,
    val firstOccurredAt: LocalDateTime?,
    val userCount: Long?,
    val deviceCount: Long,
    val stationCount: Long,
    val jiraCount: Long,
    val qualityCount: Long,
    val redblackCount: Long
)

data class ClusterFeedbackSnapshot(
    val total: Long,
    val items: List<ClusterFeedbackItemSnapshot>
)

data class ClusterFeedbackItemSnapshot(
    val sourceId: Long,
    val sourceRecordId: String?,
    val title: String?,
    val description: String?,
    val sourceChannel: String,
    val reportedAt: LocalDateTime?,
    val controlledInfoTypes: List<String>,
    val attachmentCount: Int,
    val screenshotCount: Int,
    val logCount: Int,
    val videoCount: Int,
    val decisionType: String?,
    val matchSimilarity: BigDecimal?,
    val decisionReason: String?,
    val aiModel: String?,
    val aiConfidence: BigDecimal?,
    val aiAction: String?,
    val aiExplanation: String?
)
