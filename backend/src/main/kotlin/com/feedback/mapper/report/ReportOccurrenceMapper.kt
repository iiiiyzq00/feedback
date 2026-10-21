package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper
interface ReportOccurrenceMapper {
    fun deleteDeliveryAttempts(@Param("ids") ids: List<Long>): Int
}

