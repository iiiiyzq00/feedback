package com.feedback.service.query.analysis

import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterShadowPreviewQuery(
    val page: Int = 1,
    val size: Int = 20,
    val largeOnly: Boolean = false,
    val provisionalOnly: Boolean = false,
    val crossChannelOnly: Boolean = false,
    val application: String? = null,
    val analysisSection: String? = null
)

data class ClusterShadowPreviewGenerationResponse(
    val generationKey: String,
    val status: String,
    val algorithmVersion: String,
    val modelVersion: String,
    val inputCount: Long,
    val actionableCount: Long,
    val terminalCount: Long,
    val clusterCount: Long,
    val provisionalCount: Long,
    val acceptThreshold: BigDecimal,
    val provisionalThreshold: BigDecimal,
    val semanticWeight: BigDecimal,
    val verifiedAt: LocalDateTime?,
    val terminalReasons: List<ClusterShadowTerminalReasonResponse>
)

data class ClusterShadowTerminalReasonResponse(
    val status: String,
    val label: String,
    val count: Long,
    val explanation: String
)

data class ClusterShadowBoundaryCoverageResponse(
    val application: Long,
    val failureMode: Long,
    val target: Long,
    val action: Long,
    val errorSignature: Long,
    val conflictCount: Int = 0
)

data class ClusterShadowChannelCountResponse(val channel: String, val count: Long)

data class ClusterShadowPreviewItemResponse(
    val previewId: String,
    /** 面向工程师的问题名。应用是语义前缀，不是单独的展示维度。 */
    val problem: String,
    val phenomenon: String,
    val memberCount: Long,
    val application: String,
    val provisional: Boolean,
    val provisionalMemberCount: Long,
    val large: Boolean,
    val crossChannel: Boolean,
    val channels: List<ClusterShadowChannelCountResponse>,
    val boundaries: ClusterShadowBoundaryCoverageResponse
)

data class ClusterShadowAnalysisSectionResponse(
    val key: String,
    val label: String,
    val recordCount: Long,
    val clusterCount: Long?,
    val detailAvailable: Boolean,
    val explanation: String
)

data class ClusterShadowPreviewPageResponse(
    val generation: ClusterShadowPreviewGenerationResponse,
    val analysisSections: List<ClusterShadowAnalysisSectionResponse>,
    val items: List<ClusterShadowPreviewItemResponse>,
    val total: Long,
    val page: Int,
    val size: Int,
    val generatedAt: LocalDateTime = LocalDateTime.now(),
    val readOnly: Boolean = true
)

data class ClusterShadowAnonymousMemberResponse(
    val anonymousId: String,
    val channel: String,
    val application: String,
    val decision: String,
    val score: BigDecimal?,
    val provisional: Boolean,
    val boundaryKnown: ClusterShadowBoundaryKnownResponse
)

data class ClusterShadowBoundaryKnownResponse(
    val application: Boolean,
    val failureMode: Boolean,
    val target: Boolean,
    val action: Boolean,
    val errorSignature: Boolean
)

data class ClusterShadowPreviewDetailResponse(
    val cluster: ClusterShadowPreviewItemResponse,
    val members: List<ClusterShadowAnonymousMemberResponse>,
    val total: Long,
    val page: Int,
    val size: Int,
    val bodyProjectionAvailable: Boolean = false,
    val bodyProjectionReason: String = "SHADOW_CANDIDATE_HAS_NO_SAFE_BODY_PROJECTION"
)

interface ClusterShadowPreviewQueryUseCase {
    fun list(query: ClusterShadowPreviewQuery): ClusterShadowPreviewPageResponse
    fun detail(previewId: String, page: Int, size: Int): ClusterShadowPreviewDetailResponse
}
