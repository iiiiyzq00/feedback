package com.feedback.model.po.cluster

import java.time.LocalDateTime

data class ClusterShadowGenerationPo(
    var id: Long = 0,
    var generationKey: String = "",
    var generationStatus: String = "BUILDING",
    var algorithmVersion: String = "",
    var modelVersion: String = "",
    var buildFenceSourceId: Long = 0,
    var expectedSourceCount: Long = 0,
    var processedCount: Long = 0,
    var actionableCount: Long = 0,
    var terminalCount: Long = 0,
    var clusterCount: Long = 0,
    var provisionalCount: Long = 0,
    var inputDigest: String? = null,
    var assignmentDigest: String? = null,
    var reverseAssignmentDigest: String? = null,
    var verificationDigest: String? = null,
    var failureCode: String? = null,
    var failureSummary: String? = null,
    var createdBy: Long = 0,
    var verifiedBy: Long? = null,
    var verifiedAt: LocalDateTime? = null,
    var createdAt: LocalDateTime? = null,
    var updatedAt: LocalDateTime? = null
)
