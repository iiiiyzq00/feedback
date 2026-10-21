package com.feedback.model.po.audit

data class BusinessAuditInsertPo(
    val actor: Long?,
    val operation: String,
    val auditClass: String,
    val outcome: String,
    val targetType: String,
    val targetId: String?,
    val requestId: String,
    val detailJson: String,
    val previousHash: String?,
    val eventHash: String,
    val clientIp: String?,
)
