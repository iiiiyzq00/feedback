package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterLegacyInsertPo(
    var id: Long? = null,
    val publicId: ByteArray,
    val clusterName: String,
    val clusterNameKey: String,
    val productScope: String?,
    val functionModule: String?,
    val issueCategory: String?,
    val canonicalSymptom: String?,
    val canonicalObject: String?,
    val canonicalTrigger: String?,
    val errorSignature: String?,
    val totalRecordCount: Int,
    val channelDistribution: String?,
    val clusterStatus: String,
    val lifecycleVersion: Long,
    val statisticsVersion: Long,
    val mergedIntoClusterId: Long?,
    val splitFromClusterId: Long?,
    val statusChangedBy: Long?,
    val statusChangedAt: LocalDateTime?,
    val statusReason: String?,
    val vectorIndexStatus: String?,
    val searchIndexStatus: String?
)

