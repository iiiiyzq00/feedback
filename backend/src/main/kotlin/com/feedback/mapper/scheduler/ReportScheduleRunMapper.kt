package com.feedback.mapper.scheduler

import com.feedback.model.po.scheduler.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDate

@Mapper interface ReportScheduleRunMapper {
    fun insert(@Param("row") row: ReportScheduleRunInsertPo): Int
    fun succeeded(@Param("id") id: Long, @Param("report") report: Long): Int
    fun skipped(@Param("id") id: Long): Int
    fun failed(@Param("id") id: Long, @Param("code") code: String, @Param("message") message: String): Int
}

