package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterPo(
    var id: Long = 0,
    var publicId: ByteArray = ByteArray(0),
    var clusterName: String = "",
    var clusterNameKey: String = "",
    var productScope: String? = null,
    var functionModule: String? = null,
    var issueCategory: String? = null,
    var canonicalSymptom: String? = null,
    var canonicalObject: String? = null,
    var canonicalTrigger: String? = null,
    var errorSignature: String? = null,
    var totalRecordCount: Int = 0,
    var channelDistribution: String? = null,
    var clusterStatus: String = "ACTIVE",
    var lifecycleVersion: Long = 0,
    var statisticsVersion: Long = 0,
    var statisticsReconciledAt: LocalDateTime? = null,
    var mergedIntoClusterId: Long? = null,
    var splitFromClusterId: Long? = null,
    var statusChangedBy: Long? = null,
    var statusChangedAt: LocalDateTime? = null,
    var statusReason: String? = null,
    var vectorIndexStatus: String? = null,
    var searchIndexStatus: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.MIN,
    var updatedAt: LocalDateTime = LocalDateTime.MIN
)

