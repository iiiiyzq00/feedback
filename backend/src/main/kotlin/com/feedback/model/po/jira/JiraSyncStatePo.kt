package com.feedback.model.po.jira

import java.time.LocalDateTime

data class JiraSyncStatePo(
    val connectionKey: String,
    val status: String,
    val watermark: LocalDateTime?,
    val requestedFrom: LocalDateTime?,
    val requestedTo: LocalDateTime?,
    val requestedTrigger: String?,
    val requestedBy: Long?,
    val lastAttemptAt: LocalDateTime?,
    val lastSuccessAt: LocalDateTime?,
    val nextRunAt: LocalDateTime?,
    val lastBatchId: Long?,
    val failureCount: Int,
    val lastErrorCode: String?,
    val lastErrorDetail: String?
)
