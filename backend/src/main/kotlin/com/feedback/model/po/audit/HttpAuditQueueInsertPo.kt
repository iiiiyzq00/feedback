package com.feedback.model.po.audit

data class HttpAuditQueueInsertPo(
    val actor: Long?,
    val operation: String,
    val auditClass: String,
    val outcome: String,
    val targetType: String,
    val targetId: String?,
    val requestId: String,
    val clientIp: String?,
    val detailJson: String,
)
