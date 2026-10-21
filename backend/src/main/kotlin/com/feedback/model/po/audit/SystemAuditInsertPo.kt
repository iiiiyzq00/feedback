package com.feedback.model.po.audit

data class SystemAuditInsertPo(
    val userId: Long?, val operation: String, val auditClass: String? = null,
    val outcome: String? = null, val targetType: String, val targetId: String,
    val operationDetailJson: String, val ipAddress: String? = null,
)
