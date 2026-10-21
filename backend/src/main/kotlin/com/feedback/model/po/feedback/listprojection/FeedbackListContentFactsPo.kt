package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListContentFactsPo(
    var sourceId: Long = 0,
    var title: String? = null,
    var description: String? = null,
    var functionModule: String? = null,
    var problemType: String? = null,
    var issueCategory: String? = null,
    var severityLevel: String? = null,
    var userSentiment: String? = null,
    var reproducibility: String? = null,
    var processingStatus: String = "OPEN",
    var maskingStatus: String = "",
    var deleted: Boolean = false,
    var updatedAt: LocalDateTime? = null
)

