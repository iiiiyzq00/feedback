package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ReportGenerationTaskMapper {
    fun insert(@Param("row") row: ReportTaskInsertPo): Int
    fun claim(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun lock(@Param("id") id: Long): ReportTaskLeasePo?
    fun markCancelled(@Param("id") id: Long): Int
    fun complete(@Param("id") id: Long, @Param("report") report: Long): Int
    fun failed(@Param("id") id: Long, @Param("holder") holder: String, @Param("code") code: String, @Param("detail") detail: String): Int
    fun retryable(@Param("id") id: Long): ReportTaskViewPo?
    fun queueRetry(@Param("id") id: Long, @Param("actor") actor: Long?): Int
    fun view(@Param("id") id: Long, @Param("actor") actor: Long?): ReportTaskViewPo?
    fun list(@Param("actor") actor: Long?): List<ReportTaskViewPo>
    fun due(): List<ReportTaskDuePo>
    fun cancel(@Param("id") id: Long, @Param("actor") actor: Long?): Int
    fun recoverExpired(): Int
}

