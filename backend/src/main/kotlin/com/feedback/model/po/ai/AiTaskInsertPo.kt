package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiTaskInsertPo(
    var id: Long? = null,
    val skillCode: String,
    val promptVersion: String,
    val sourceId: Long,
    val taskKey: String,
    val taskStatus: String,
    val inputSnapshot: String,
    val snapshotRetentionUntil: LocalDateTime
)

