package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReclassificationArchivePo(
    val id: Long,
    val reason: String,
    val userId: Long?,
    val clearStatistics: Boolean,
    val onlyOperational: Boolean,
    val updateActor: Boolean
)

