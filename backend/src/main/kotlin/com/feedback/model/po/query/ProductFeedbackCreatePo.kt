package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ProductFeedbackCreatePo(
    var id: Long = 0, val requestNo: String, val requestType: String, val title: String, val description: String,
    val sourceChannel: String?, val feedbackSourceId: Long?, val pagePath: String, val pageContextJson: String,
    val attachmentsJson: String, val createdBy: Long
)

