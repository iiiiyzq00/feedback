package com.feedback.model.po.feedback.listprojection

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FeedbackListProjectionDirtyPo(
    var sourceId: Long = 0,
    var mutationVersion: Long = 0,
    var dirtyReason: String = "",
    var taskStatus: String = "PENDING",
    var retryCount: Int = 0,
    var nextRetryAt: LocalDateTime? = null,
    var leaseOwner: String? = null,
    var leaseExpiresAt: LocalDateTime? = null,
    var lastErrorCode: String? = null,
    var lastErrorSummary: String? = null,
    var createdAt: LocalDateTime? = null,
    var updatedAt: LocalDateTime? = null
)

