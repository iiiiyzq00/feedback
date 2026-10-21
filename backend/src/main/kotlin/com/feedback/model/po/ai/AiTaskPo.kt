package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiTaskPo(
    var id: Long = 0,
    var recordType: String = "INVOCATION",
    var skillCode: String? = null,
    var promptVersion: String? = null,
    var sourceId: Long? = null,
    var taskKey: String? = null,
    var taskStatus: String? = null,
    var leaseHolder: String? = null,
    var leaseExpiresAt: LocalDateTime? = null,
    var leaseGeneration: Long = 0,
    var nextRetryAt: LocalDateTime? = null,
    var retryCount: Int = 0,
    var inputSnapshot: String? = null,
    var outputSnapshot: String? = null,
    var snapshotRetentionUntil: LocalDateTime? = null,
    var errorDetail: String? = null,
    var completedAt: LocalDateTime? = null,
    var createdAt: LocalDateTime = LocalDateTime.MIN,
    var updatedAt: LocalDateTime = LocalDateTime.MIN
)

