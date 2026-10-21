package com.feedback.model.po.audit

data class BusinessAuditChainPo(
    var id: Long = 0,
    var userId: Long? = null,
    var operation: String = "",
    var auditClass: String = "",
    var outcome: String = "",
    var targetType: String = "",
    var targetId: String? = null,
    var requestId: String = "",
    var operationDetail: String = "{}",
    var previousEventHash: String? = null,
    var eventHash: String? = null,
)
