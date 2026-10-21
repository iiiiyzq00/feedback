package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ClusterReclassificationSnapshotMapper {
    fun insertAll(@Param("rows") rows: List<ClusterReclassificationSnapshotPo>): Int
    fun findByRunId(@Param("runId") runId: Long): List<ClusterReclassificationSnapshotPo>
}

