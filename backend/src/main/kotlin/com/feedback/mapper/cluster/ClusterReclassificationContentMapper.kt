package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ClusterReclassificationContentMapper {
    fun countSafe(): Long
    fun findSafeAfter(
        @Param("createdAt") createdAt: LocalDateTime?,
        @Param("sourceId") sourceId: Long,
        @Param("limit") limit: Int
    ): List<ClusterReclassificationContentCursorPo>
}

