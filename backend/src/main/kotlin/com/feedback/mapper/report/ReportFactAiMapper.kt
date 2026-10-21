package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper interface ReportFactAiMapper {
    fun bySourceIds(@Param("ids") ids: List<Long>): List<ReportFactAiPo>
    fun pendingReviewCount(@Param("to") to: LocalDateTime): Long
}

