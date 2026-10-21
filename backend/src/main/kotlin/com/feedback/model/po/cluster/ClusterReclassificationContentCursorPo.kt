package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReclassificationContentCursorPo(
    var sourceId: Long = 0,
    var createdAt: LocalDateTime = LocalDateTime.MIN
)

