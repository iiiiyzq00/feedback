package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper interface ReportFactImportBatchMapper {
    fun pageCreated(
        @Param("from") from: LocalDateTime?,
        @Param("to") to: LocalDateTime?,
        @Param("channel") channel: String?,
        @Param("afterId") afterId: Long,
        @Param("limit") limit: Int,
    ): List<ReportFactImportBatchPo>
    fun byIds(@Param("ids") ids: List<Long>): List<ReportFactImportBatchPo>
}

