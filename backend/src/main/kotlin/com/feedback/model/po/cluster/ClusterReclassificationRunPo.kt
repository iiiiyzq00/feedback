package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReclassificationRunPo(
    var id: Long? = null,
    var publicId: ByteArray? = null,
    var requestId: String = "",
    var runStatus: String = "",
    var reason: String = "",
    var requestedBy: Long? = null,
    var snapshotSafeCount: Long = 0,
    var processedCount: Long = 0,
    var linkedExistingCount: Long = 0,
    var newClusterCount: Long = 0,
    var errorDetail: String? = null,
    var leaseHolder: String? = null,
    var leaseExpiresAt: LocalDateTime? = null,
    var startedAt: LocalDateTime? = null,
    var completedAt: LocalDateTime? = null,
    var rolledBackAt: LocalDateTime? = null,
    var createdAt: LocalDateTime? = null,
    var updatedAt: LocalDateTime? = null
)

