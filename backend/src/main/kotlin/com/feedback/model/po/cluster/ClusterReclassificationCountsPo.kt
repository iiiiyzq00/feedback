package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReclassificationCountsPo(
    var processed: Long = 0,
    var linkedExisting: Long = 0,
    var newClusters: Long = 0
)

