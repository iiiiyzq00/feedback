package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportFactContentPo(
    var sourceId: Long = 0,
    var title: String? = null,
    var description: String? = null,
    var functionModule: String? = null,
    var issueCategory: String? = null,
    var severityLevel: String? = null,
    var userSentiment: String? = null,
    var maskingStatus: String = "",
    var mappingError: String? = null,
    var dataQualityScore: BigDecimal? = null,
    var deleted: Boolean = false,
)

