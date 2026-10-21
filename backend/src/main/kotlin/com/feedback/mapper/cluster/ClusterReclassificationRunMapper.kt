package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ClusterReclassificationRunMapper {
    fun findById(@Param("id") id: Long): ClusterReclassificationRunPo?
    fun lockById(@Param("id") id: Long): ClusterReclassificationRunPo?
    fun findByRequestId(@Param("requestId") requestId: String): ClusterReclassificationRunPo?
    fun findLatest(): ClusterReclassificationRunPo?
    fun lockAnyActive(@Param("excludeId") excludeId: Long?): Long?
    fun insert(@Param("row") row: ClusterReclassificationRunPo): Int
    fun claimable(@Param("holder") holder: String): Long?
    fun claim(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun renew(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun beginFinalizing(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun complete(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun beginRollback(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun completeRollback(@Param("id") id: Long): Int
    fun resumeFailed(@Param("id") id: Long): Int
    fun resumeFailedOrCompleted(@Param("id") id: Long): Int
    fun fail(@Param("id") id: Long, @Param("holder") holder: String?, @Param("detail") detail: String): Int
    fun updateSnapshotSafeCount(@Param("id") id: Long, @Param("safe") safe: Long): Int
    fun updateCounts(@Param("id") id: Long, @Param("counts") counts: ClusterReclassificationCountsPo): Int
}

