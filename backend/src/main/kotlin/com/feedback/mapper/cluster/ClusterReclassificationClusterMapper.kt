package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ClusterReclassificationClusterMapper {
    fun findOperationalAfter(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<ClusterPo>
    fun findInvalidOperationalByIds(@Param("ids") ids: List<Long>): List<Long>
    fun archiveAll(@Param("rows") rows: List<ClusterReclassificationArchivePo>): Int
    fun restoreAll(@Param("rows") rows: List<ClusterReclassificationSnapshotPo>): Int
    fun countDuplicateOperationalNames(): Long
    fun countInvalidOperationalNames(): Long
}

