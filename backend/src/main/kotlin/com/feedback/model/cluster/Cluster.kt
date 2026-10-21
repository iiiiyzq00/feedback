package com.feedback.model.cluster

import java.time.LocalDateTime

/** 问题簇领域快照；生命周期与统计版本用于隔离并发写入。 */
data class Cluster(
    val id: Long = 0,
    val publicId: ByteArray = ByteArray(16),
    val clusterName: String = "",
    val clusterNameKey: String = stableClusterNameKey(clusterName),
    val productScope: String? = null,
    val functionModule: String? = null,
    val issueCategory: String? = null,
    val canonicalSymptom: String? = null,
    val canonicalObject: String? = null,
    val canonicalTrigger: String? = null,
    val errorSignature: String? = null,
    val totalRecordCount: Int = 0,
    val channelDistribution: String? = null,
    val clusterStatus: String = "ACTIVE",
    val lifecycleVersion: Long = 0,
    val statisticsVersion: Long = 0,
    val statisticsReconciledAt: LocalDateTime? = null,
    val mergedIntoClusterId: Long? = null,
    val splitFromClusterId: Long? = null,
    val statusChangedBy: Long? = null,
    val statusChangedAt: LocalDateTime? = null,
    val statusReason: String? = null,
    val vectorIndexStatus: String? = null,
    val searchIndexStatus: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
