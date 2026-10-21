package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackClusterMemberProjectionPo(
    var generationId: Long = 0,
    var sourceId: Long = 0,
    var clusterId: Long = 0,
    var sourceChannel: String = "",
    var logicalChannel: String = "",
    var clusterStatus: String = "ACTIVE",
    var reportedAt: LocalDateTime? = null,
    var functionModule: String? = null,
    var provinceCode: String? = null,
    var cityCode: String? = null,
    var applicationName: String? = null,
    var regionValue: String? = null,
    var attachmentCount: Int = 0,
    var screenshotCount: Int = 0,
    var logCount: Int = 0,
    var videoCount: Int = 0,
    var decisionType: String? = null,
    var matchSimilarity: BigDecimal? = null,
    var decisionReason: String? = null,
    var aiModel: String? = null,
    var aiConfidence: BigDecimal? = null,
    var aiAction: String? = null,
    var aiExplanation: String? = null
)

