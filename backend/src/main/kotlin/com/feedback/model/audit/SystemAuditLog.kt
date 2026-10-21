package com.feedback.model.audit

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDateTime

/** 不可变系统审计事实；持久化只允许通过 MyBatis Repository 边界。 */
data class SystemAuditLog(
    var id: Long? = null,
    val userId: Long? = null,
    val operation: String,
    val targetType: String,
    val targetId: String?,
    val operationDetail: JsonNode,
    val ipAddress: String?,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
