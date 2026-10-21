package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ReportIdempotencyMapper {
    fun completedBase(@Param("base") base: String): Int
    fun compatible(@Param("key") key: String): ReportIdempotencyResultPo?
    fun insert(@Param("row") row: ReportIdempotencyInsertPo): Int
    fun byHash(@Param("key") key: String): ReportIdempotencyResultPo?
    fun regenerationNumbers(@Param("base") base: String): List<Int>
    fun complete(@Param("key") key: String, @Param("report") report: Long): Int
    fun previous(@Param("base") base: String, @Param("number") number: Int): ReportIdempotencyResultPo?
}

