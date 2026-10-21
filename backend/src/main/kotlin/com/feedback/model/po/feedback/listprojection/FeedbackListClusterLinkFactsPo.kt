package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListClusterLinkFactsPo(
    var feedbackSourceId: Long = 0,
    var clusterId: Long = 0,
    var linkType: String? = null,
    var matchSimilarity: BigDecimal? = null,
    var decisionReason: String? = null,
    var updatedAt: LocalDateTime? = null
) {
    constructor(feedbackSourceId: Long, clusterId: Long, updatedAt: LocalDateTime?) :
        this(feedbackSourceId, clusterId, null, null, null, updatedAt)
}

