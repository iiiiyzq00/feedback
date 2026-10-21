package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper
interface ReportMapper {
    fun page(@Param("type") type: String?, @Param("beforeTime") beforeTime: LocalDateTime?, @Param("beforeId") beforeId: Long?, @Param("limit") limit: Int): List<ReportListPo>
    fun detail(@Param("id") id: Long): ReportDetailPo?
    fun lockForDelete(@Param("id") id: Long): ReportDeletePo?
    fun insert(@Param("row") row: ReportInsertPo): Int
    fun updateMetadata(@Param("row") row: ReportMetadataUpdatePo): Int
    fun delete(@Param("id") id: Long): Int
}

