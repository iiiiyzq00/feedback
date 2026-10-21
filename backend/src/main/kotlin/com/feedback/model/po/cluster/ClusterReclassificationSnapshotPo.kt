package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReclassificationSnapshotPo(
    val runId: Long,
    val clusterId: Long,
    val originalStatus: String,
    val originalStatusReason: String?,
    val originalStatusChangedBy: Long?,
    val originalStatusChangedAt: LocalDateTime?,
    val originalLifecycleVersion: Long
)

